// FP32-accumulating ARM64 CPU implementation of Krea's text-fusion/projector.
// It consumes selected Qwen3-VL hidden states and writes the padded Krea context.
#include <arm_neon.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

using f32 = float;
static constexpr int D=2560, HEADS=20, HD=128, NL=12, PREFIX=34, SUFFIX=5, MAXT=512;
static constexpr int LAYERS[NL]={2,5,8,11,14,17,20,23,26,29,32,35};
#pragma pack(push,1)
struct Entry { char name[128]; uint64_t count,offset; uint32_t dtype,reserved; };
#pragma pack(pop)
struct Weight { const uint8_t* p=nullptr; uint64_t count=0; uint32_t dtype=0; };
static uint8_t* g_pack=nullptr; static size_t g_pack_size=0;
static std::unordered_map<std::string,Weight> g_w;
static const Weight& wt(const std::string& n){auto i=g_w.find(n);if(i==g_w.end()){fprintf(stderr,"missing fusion tensor: %s\n",n.c_str());std::exit(2);}return i->second;}
static float32x4_t load4(const Weight& w,size_t i){return w.dtype==0?vcvt_f32_f16(vld1_f16((const __fp16*)w.p+i)):vld1q_f32((const f32*)w.p+i);}
static float load1(const Weight& w,size_t i){return w.dtype==0?(float)((const __fp16*)w.p)[i]:((const f32*)w.p)[i];}

static void load_pack(const char* path){
  int fd=open(path,O_RDONLY);if(fd<0){perror("open weights");std::exit(2);}struct stat st{};if(fstat(fd,&st)||st.st_size<8){fprintf(stderr,"bad fusion pack size\n");std::exit(2);}g_pack_size=(size_t)st.st_size;
  g_pack=(uint8_t*)mmap(nullptr,g_pack_size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(g_pack==MAP_FAILED){perror("mmap weights");std::exit(2);}
  if(memcmp(g_pack,"KTF1",4)){fprintf(stderr,"bad KTF1 magic\n");std::exit(2);}uint32_t n;memcpy(&n,g_pack+4,4);
  if(8ull+(uint64_t)n*sizeof(Entry)>g_pack_size){fprintf(stderr,"truncated KTF1 table\n");std::exit(2);}const Entry* e=(const Entry*)(g_pack+8);
  for(uint32_t i=0;i<n;i++){uint64_t bytes=e[i].count*(e[i].dtype==0?2ull:4ull);if(e[i].dtype>1||e[i].offset+bytes>g_pack_size){fprintf(stderr,"invalid KTF1 tensor %u\n",i);std::exit(2);}g_w.emplace(std::string(e[i].name,strnlen(e[i].name,sizeof e[i].name)),Weight{g_pack+e[i].offset,e[i].count,e[i].dtype});}
  fprintf(stderr,"loaded %u text tensors (%.1f MiB mmap)\n",n,g_pack_size/1048576.0);
}

static void gemm(const Weight& W,const f32* X,f32* Y,int n,int out,int in){
  constexpr int OB=4;std::atomic<int> next{0};const int nb=(out+OB-1)/OB;
  auto worker=[&]{std::vector<f32>w((size_t)OB*in);for(;;){int b=next.fetch_add(1);if(b>=nb)break;int o0=b*OB,ob=std::min(OB,out-o0);
    for(int r=0;r<OB;r++){f32* wr=w.data()+(size_t)r*in;if(r<ob){const size_t base=(size_t)(o0+r)*in;for(int k=0;k<in;k+=4)vst1q_f32(wr+k,load4(W,base+k));}else memset(wr,0,(size_t)in*4);}
    const f32*w0=w.data(),*w1=w0+in,*w2=w1+in,*w3=w2+in;int t=0;
    for(;t+4<=n;t+=4){const f32*x0=X+(size_t)t*in,*x1=x0+in,*x2=x1+in,*x3=x2+in;float32x4_t a[4][4];for(int i=0;i<4;i++)for(int j=0;j<4;j++)a[i][j]=vdupq_n_f32(0);
      for(int k=0;k<in;k+=4){float32x4_t ww[4]={vld1q_f32(w0+k),vld1q_f32(w1+k),vld1q_f32(w2+k),vld1q_f32(w3+k)};const f32*xx[4]={x0+k,x1+k,x2+k,x3+k};for(int i=0;i<4;i++){float32x4_t xv=vld1q_f32(xx[i]);for(int j=0;j<4;j++)a[i][j]=vfmaq_f32(a[i][j],xv,ww[j]);}}
      for(int i=0;i<4;i++)for(int j=0;j<ob;j++)Y[(size_t)(t+i)*out+o0+j]=vaddvq_f32(a[i][j]);}
    for(;t<n;t++){const f32*x=X+(size_t)t*in;for(int j=0;j<ob;j++){float32x4_t a=vdupq_n_f32(0);for(int k=0;k<in;k+=4)a=vfmaq_f32(a,vld1q_f32(x+k),vld1q_f32(w.data()+(size_t)j*in+k));Y[(size_t)t*out+o0+j]=vaddvq_f32(a);}}
  }};int nt=std::max(1,std::min(8,(int)std::thread::hardware_concurrency()));std::vector<std::thread>th;for(int i=1;i<nt;i++)th.emplace_back(worker);worker();for(auto&t:th)t.join();
}

static void linear(const f32*x,f32*y,int n,int out,int in,const std::string& name){gemm(wt(name),x,y,n,out,in);}
static void add_bias(f32*y,int n,int d,const Weight&b){for(int r=0;r<n;r++)for(int c=0;c<d;c++)y[(size_t)r*d+c]+=load1(b,c);}
static void rms_rows(const f32*x,f32*y,int rows,int d,const Weight&scale){for(int r=0;r<rows;r++){const f32*a=x+(size_t)r*d;f32*b=y+(size_t)r*d;float ss=0;for(int c=0;c<d;c++)ss+=a[c]*a[c];float inv=1.f/sqrtf(ss/d+1e-5f);for(int c=0;c<d;c++)b[c]=a[c]*inv*(load1(scale,c)+1.f);}}
static void rms_heads(f32*x,int groups,int seq,const Weight&scale){for(int g=0;g<groups;g++)for(int s=0;s<seq;s++)for(int h=0;h<HEADS;h++){f32*a=x+((size_t)g*seq+s)*D+h*HD;float ss=0;for(int c=0;c<HD;c++)ss+=a[c]*a[c];float inv=1.f/sqrtf(ss/HD+1e-5f);for(int c=0;c<HD;c++)a[c]=a[c]*inv*(load1(scale,c)+1.f);}}
static void attention(f32*q,f32*k,const f32*v,f32*out,int groups,int seq){std::vector<f32>scores(seq);const float scale=1.f/sqrtf((float)HD);
  for(int g=0;g<groups;g++)for(int h=0;h<HEADS;h++)for(int qi=0;qi<seq;qi++){float mx=-INFINITY;for(int kj=0;kj<seq;kj++){float s=0;const f32*qr=q+((size_t)g*seq+qi)*D+h*HD;const f32*kr=k+((size_t)g*seq+kj)*D+h*HD;for(int d=0;d<HD;d++)s+=qr[d]*kr[d];scores[kj]=s*scale;mx=std::max(mx,scores[kj]);}float sum=0;for(float&s:scores){s=expf(s-mx);sum+=s;}f32*dst=out+((size_t)g*seq+qi)*D+h*HD;for(int d=0;d<HD;d++){float a=0;for(int kj=0;kj<seq;kj++)a+=scores[kj]*v[((size_t)g*seq+kj)*D+h*HD+d];dst[d]=a/sum;}}
}
static void fusion_block(f32*x,int groups,int seq,const std::string&p){const int rows=groups*seq;const std::string a=p+".attn.",m=p+".mlp.";std::vector<f32>z((size_t)rows*D),q(z.size()),k(z.size()),v(z.size()),gate(z.size()),att(z.size()),tmp(z.size());
  rms_rows(x,z.data(),rows,D,wt(p+".prenorm.scale"));linear(z.data(),q.data(),rows,D,D,a+"wq.weight");linear(z.data(),k.data(),rows,D,D,a+"wk.weight");linear(z.data(),v.data(),rows,D,D,a+"wv.weight");linear(z.data(),gate.data(),rows,D,D,a+"gate.weight");
  rms_heads(q.data(),groups,seq,wt(a+"qknorm.qnorm.scale"));rms_heads(k.data(),groups,seq,wt(a+"qknorm.knorm.scale"));attention(q.data(),k.data(),v.data(),att.data(),groups,seq);
  for(size_t i=0;i<gate.size();i++)att[i]*=1.f/(1.f+expf(-gate[i]));linear(att.data(),tmp.data(),rows,D,D,a+"wo.weight");
  for(size_t i=0;i<(size_t)rows*D;i++)x[i]+=tmp[i];rms_rows(x,z.data(),rows,D,wt(p+".postnorm.scale"));const int M=6912;std::vector<f32>g((size_t)rows*M),u(g.size());linear(z.data(),g.data(),rows,M,M?D:D,m+"gate.weight");linear(z.data(),u.data(),rows,M,D,m+"up.weight");for(size_t i=0;i<g.size();i++){float a0=g[i];g[i]=(a0/(1.f+expf(-a0)))*u[i];}linear(g.data(),tmp.data(),rows,D,M,m+"down.weight");for(size_t i=0;i<(size_t)rows*D;i++)x[i]+=tmp[i];
}

static std::vector<f32> read_hidden(const std::string&p,int seq){std::vector<f32>x((size_t)seq*D);std::ifstream f(p,std::ios::binary);if(!f||!f.read((char*)x.data(),x.size()*4)||f.peek()!=EOF){fprintf(stderr,"invalid hidden state: %s\n",p.c_str());std::exit(2);}for(float v:x)if(!std::isfinite(v)){fprintf(stderr,"non-finite hidden state: %s\n",p.c_str());std::exit(2);}return x;}
#ifndef KREA_TEXT_FUSE_LIBRARY
int main(int argc,char**argv){if(argc<5||argc>7){fprintf(stderr,"usage: krea_text_fuse_cpu WEIGHTS.kft HIDDEN_DIR CONTEXT.f32 MASK [PROMPT=17] [SEQ=546]\n");return 2;}int prompt=argc>5?atoi(argv[5]):17,seq=argc>6?atoi(argv[6]):546;if(prompt<1||prompt+SUFFIX>MAXT||seq<PREFIX+prompt||seq<SUFFIX){fprintf(stderr,"invalid token dimensions\n");return 2;}load_pack(argv[1]);const int active=prompt+SUFFIX;std::vector<f32>x((size_t)active*NL*D);
  for(int l=0;l<NL;l++){char fn[32];snprintf(fn,sizeof fn,"hidden_%02d.f32",LAYERS[l]);auto h=read_hidden(std::string(argv[2])+"/"+fn,seq);for(int t=0;t<prompt;t++)memcpy(x.data()+((size_t)t*NL+l)*D,h.data()+(size_t)(PREFIX+t)*D,D*4);for(int t=0;t<SUFFIX;t++)memcpy(x.data()+((size_t)(prompt+t)*NL+l)*D,h.data()+(size_t)(seq-SUFFIX+t)*D,D*4);}
  for(int i=0;i<2;i++)fusion_block(x.data(),active,NL,"txtfusion.layerwise_blocks."+std::to_string(i));
  std::vector<f32>y((size_t)active*D);const Weight&proj=wt("txtfusion.projector.weight");for(int t=0;t<active;t++)for(int d=0;d<D;d++){float z=0;for(int l=0;l<NL;l++)z+=x[((size_t)t*NL+l)*D+d]*load1(proj,l);y[(size_t)t*D+d]=z;}
  for(int i=0;i<2;i++)fusion_block(y.data(),1,active,"txtfusion.refiner_blocks."+std::to_string(i));
  std::vector<f32>z(y.size());rms_rows(y.data(),z.data(),active,D,wt("txtmlp.0.scale"));const int OUT=6144;std::vector<f32>h((size_t)active*OUT),out(h.size());linear(z.data(),h.data(),active,OUT,D,"txtmlp.1.weight");add_bias(h.data(),active,OUT,wt("txtmlp.1.bias"));for(float&a:h){const float c=0.7978845608028654f;a=0.5f*a*(1.f+tanhf(c*(a+0.044715f*a*a*a)));}linear(h.data(),out.data(),active,OUT,OUT,"txtmlp.3.weight");add_bias(out.data(),active,OUT,wt("txtmlp.3.bias"));
  std::vector<f32>ctx((size_t)MAXT*OUT,0.f);for(int t=0;t<prompt;t++)memcpy(ctx.data()+(size_t)t*OUT,out.data()+(size_t)t*OUT,OUT*4);for(int t=0;t<SUFFIX;t++)memcpy(ctx.data()+(size_t)(MAXT-SUFFIX+t)*OUT,out.data()+(size_t)(prompt+t)*OUT,OUT*4);
  std::vector<uint8_t>mask(MAXT,0);for(int t=0;t<prompt;t++)mask[t]=1;for(int t=MAXT-SUFFIX;t<MAXT;t++)mask[t]=1;std::ofstream fo(argv[3],std::ios::binary);fo.write((char*)ctx.data(),ctx.size()*4);std::ofstream fm(argv[4],std::ios::binary);fm.write((char*)mask.data(),mask.size());float ss=0,mn=INFINITY,mx=-INFINITY;for(int t=0;t<active*OUT;t++){float a=out[t];if(!std::isfinite(a)){fprintf(stderr,"non-finite output context\n");return 3;}ss+=a*a;mn=std::min(mn,a);mx=std::max(mx,a);}fprintf(stderr,"context tokens=%d finite rms=%g range=[%g,%g]\n",active,sqrtf(ss/(active*OUT)),mn,mx);return (!fo||!fm)?3:0;
}
#endif
