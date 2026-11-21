// Standalone ARM64 CPU decoder for Krea 2's official Qwen Image VAE.
// It decodes only the first temporal frame, matching Krea's still-image path.
#include <arm_neon.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

using f32 = float;
using Clock = std::chrono::steady_clock;

#pragma pack(push, 1)
struct EntryDisk { char name[128]; uint64_t count; uint64_t offset; };
#pragma pack(pop)

static std::vector<uint8_t> g_pack;
static std::unordered_map<std::string, const f32*> g_w;
struct Image;
static const char* g_debug_dir = nullptr;
static void dump_image(const char* name, const Image& x);
static double elapsed(Clock::time_point t) { return std::chrono::duration<double>(Clock::now() - t).count(); }
static const f32* wt(const std::string& n) {
  auto it = g_w.find(n);
  if (it == g_w.end()) { fprintf(stderr, "missing VAE tensor: %s\n", n.c_str()); std::exit(2); }
  return it->second;
}
static void load_pack(const char* path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) { fprintf(stderr, "cannot open weights: %s\n", path); std::exit(2); }
  const auto bytes = (size_t)f.tellg(); f.seekg(0);
  g_pack.resize(bytes); f.read((char*)g_pack.data(), bytes);
  if (!f || bytes < 8 || memcmp(g_pack.data(), "KVA1", 4)) { fprintf(stderr, "invalid KVA1 weight pack\n"); std::exit(2); }
  uint32_t n; memcpy(&n, g_pack.data() + 4, 4);
  if (8ull + (uint64_t)n * sizeof(EntryDisk) > bytes) { fprintf(stderr, "truncated KVA1 table\n"); std::exit(2); }
  const EntryDisk* e = (const EntryDisk*)(g_pack.data() + 8);
  for (uint32_t i = 0; i < n; i++) {
    if (e[i].offset + e[i].count * sizeof(f32) > bytes) { fprintf(stderr, "invalid KVA1 entry %u\n", i); std::exit(2); }
    g_w.emplace(std::string(e[i].name, strnlen(e[i].name, sizeof e[i].name)), (const f32*)(g_pack.data() + e[i].offset));
  }
  fprintf(stderr, "loaded %u tensors (%.1f MiB)\n", n, bytes / 1048576.0);
}

// Y[n,out] = X[n,in] * W[out,in]^T. Four rows are reused across four pixels.
static void gemm(const f32* W, const f32* X, f32* Y, int n, int out, int in) {
  const int OB = 4, nb = (out + OB - 1) / OB;
  std::atomic<int> next{0};
  auto worker = [&] {
    std::vector<f32> w((size_t)OB * in);
    for (;;) {
      const int b = next.fetch_add(1); if (b >= nb) break;
      const int o0 = b * OB, ob = std::min(OB, out - o0);
      for (int r = 0; r < OB; r++) {
        f32* dst = w.data() + (size_t)r * in;
        if (r < ob) memcpy(dst, W + (size_t)(o0 + r) * in, (size_t)in * 4);
        else memset(dst, 0, (size_t)in * 4);
      }
      const f32 *w0=w.data(), *w1=w0+in, *w2=w1+in, *w3=w2+in;
      int t = 0;
      for (; t + 4 <= n; t += 4) {
        const f32 *x0=X+(size_t)t*in, *x1=x0+in, *x2=x1+in, *x3=x2+in;
        float32x4_t a[4][4]; for (int i=0;i<4;i++) for(int j=0;j<4;j++) a[i][j]=vdupq_n_f32(0.f);
        for (int k=0;k<in;k+=4) {
          const float32x4_t ww[4]={vld1q_f32(w0+k),vld1q_f32(w1+k),vld1q_f32(w2+k),vld1q_f32(w3+k)};
          const f32* xx[4]={x0+k,x1+k,x2+k,x3+k};
          for(int i=0;i<4;i++){const float32x4_t xv=vld1q_f32(xx[i]);for(int j=0;j<4;j++)a[i][j]=vfmaq_f32(a[i][j],xv,ww[j]);}
        }
        for(int i=0;i<4;i++)for(int j=0;j<ob;j++)Y[(size_t)(t+i)*out+o0+j]=vaddvq_f32(a[i][j]);
      }
      for (;t<n;t++) for(int j=0;j<ob;j++) {
        const f32* x=X+(size_t)t*in; const f32* wr=W+(size_t)(o0+j)*in;
        float32x4_t a=vdupq_n_f32(0.f);for(int k=0;k<in;k+=4)a=vfmaq_f32(a,vld1q_f32(x+k),vld1q_f32(wr+k));
        Y[(size_t)t*out+o0+j]=vaddvq_f32(a);
      }
    }
  };
  const int nt=std::max(1,std::min(8,(int)std::thread::hardware_concurrency()));
  std::vector<std::thread> th;for(int i=1;i<nt;i++)th.emplace_back(worker);worker();for(auto& x:th)x.join();
}

struct Image { int h=0,w=0,c=0; std::vector<f32> v; };
static Image make_image(int h,int w,int c){return {h,w,c,std::vector<f32>((size_t)h*w*c)};}
static void bias_add(Image& x,const f32* b){for(int i=0;i<x.h*x.w;i++)for(int c=0;c<x.c;c++)x.v[(size_t)i*x.c+c]+=b[c];}
static Image conv2(const Image& x,const f32* W,const f32* b,int out,int k) {
  const int in=x.c, L=x.h*x.w, K=in*k*k; Image y=make_image(x.h,x.w,out);
  constexpr int BATCH=32; std::vector<f32> col((size_t)BATCH*K);
  for(int p0=0;p0<L;p0+=BATCH){int n=std::min(BATCH,L-p0);
    for(int i=0;i<n;i++){int p=p0+i, yy=p/x.w, xx=p%x.w;f32* d=col.data()+(size_t)i*K;
      for(int ky=0;ky<k;ky++)for(int kx=0;kx<k;kx++)for(int c=0;c<in;c++){
        int sy=yy+ky-k/2,sx=xx+kx-k/2; d[(ky*k+kx)*in+c]=(sy<0||sy>=x.h||sx<0||sx>=x.w)?0.f:x.v[((size_t)sy*x.w+sx)*in+c];
      }
    }
    gemm(W,col.data(),y.v.data()+(size_t)p0*out,n,out,K);
  }
  if(b)bias_add(y,b);return y;
}
static Image conv3(const Image& x,const std::string& prefix,int out,int k=3) {
  const int in=x.c,K=in*k*k;std::vector<f32> W((size_t)out*K);
  const f32* src=wt(prefix+".weight");
  // A one-frame causal Conv3d sees only the last temporal kernel slice.
  for(int o=0;o<out;o++)for(int c=0;c<in;c++)for(int ky=0;ky<k;ky++)for(int kx=0;kx<k;kx++)
    W[(size_t)o*K+(ky*k+kx)*in+c]=src[((size_t)o*in+c)*k*k*k+2*k*k+ky*k+kx];
  return conv2(x,W.data(),wt(prefix+".bias"),out,k);
}
static Image conv1x1x1(const Image& x,const std::string& prefix,int out) {
  return conv2(x,wt(prefix+".weight"),wt(prefix+".bias"),out,1);
}
static Image upsample_conv(const Image& x,const std::string& prefix,int out) {
  Image up=make_image(x.h*2,x.w*2,x.c);
  for(int y=0;y<up.h;y++)for(int xx=0;xx<up.w;xx++)memcpy(&up.v[((size_t)y*up.w+xx)*up.c],&x.v[((size_t)(y/2)*x.w+xx/2)*x.c],(size_t)x.c*4);
  const int in=x.c,k=3,K=in*k*k;const f32* src=wt(prefix+".weight");std::vector<f32> W((size_t)out*K);
  // Convert PyTorch OIHW weights to the HWC im2col order used by conv2.
  for(int o=0;o<out;o++)for(int c=0;c<in;c++)for(int ky=0;ky<k;ky++)for(int kx=0;kx<k;kx++)
    W[(size_t)o*K+(ky*k+kx)*in+c]=src[((size_t)o*in+c)*k*k+ky*k+kx];
  return conv2(up,W.data(),wt(prefix+".bias"),out,k);
}
static void norm_only(Image& x,const std::string& name){
  const f32* g=wt(name+".gamma"); const int C=x.c;
  for(int p=0;p<x.h*x.w;p++){f32* a=x.v.data()+(size_t)p*C;float32x4_t q=vdupq_n_f32(0.f);for(int c=0;c<C;c+=4){float32x4_t z=vld1q_f32(a+c);q=vfmaq_f32(q,z,z);}float ss=vaddvq_f32(q);const float scale=sqrtf((float)C)/sqrtf(ss+1e-12f);
    for(int c=0;c<C;c++)a[c]*=scale*g[c];}
}
static void norm_silu(Image& x,const std::string& name){
  norm_only(x,name);for(float& z:x.v)z=z/(1.f+expf(-z));
}
static Image resblock(Image x,const std::string& base,int out){
  Image skip=x;if(out!=x.c)skip=conv1x1x1(x,base+".conv_shortcut",out);
  norm_silu(x,base+".norm1");x=conv3(x,base+".conv1",out);
  norm_silu(x,base+".norm2");x=conv3(x,base+".conv2",out);
  for(size_t i=0;i<x.v.size();i++)x.v[i]+=skip.v[i];return x;
}
static Image attention(Image x,const std::string& base){
  const int C=x.c,L=x.h*x.w;Image z=x;norm_only(z,base+".norm");
  Image qkv=conv2(z,wt(base+".to_qkv.weight"),wt(base+".to_qkv.bias"),3*C,1),o=make_image(x.h,x.w,C);
  std::vector<f32> score((size_t)L),att((size_t)L*C);const float inv=1.f/sqrtf((float)C);
  const f32* qkvp=qkv.v.data();
  for(int i=0;i<L;i++){
    float mx=-INFINITY;for(int j=0;j<L;j++){float s=0;for(int c=0;c<C;c++)s+=qkvp[(size_t)i*3*C+c]*qkvp[(size_t)j*3*C+C+c];s*=inv;score[j]=s;if(s>mx)mx=s;}
    float sum=0;for(int j=0;j<L;j++){score[j]=expf(score[j]-mx);sum+=score[j];}float ri=1.f/sum;
    f32* dst=att.data()+(size_t)i*C;for(int c=0;c<C;c++){float s=0;for(int j=0;j<L;j++)s+=score[j]*qkvp[(size_t)j*3*C+2*C+c];dst[c]=s*ri;}
  }
  Image tmp=make_image(x.h,x.w,C);gemm(wt(base+".proj.weight"),att.data(),tmp.v.data(),L,C,C);bias_add(tmp,wt(base+".proj.bias"));
  for(size_t i=0;i<x.v.size();i++)x.v[i]+=tmp.v[i];return x;
}
static Image decode(const f32* z,int h,int w){
  static const f32 mean[16]={-0.7571f,-0.7089f,-0.9113f,0.1075f,-0.1745f,0.9653f,-0.1517f,1.5508f,0.4134f,-0.0715f,0.5517f,-0.3632f,-0.1922f,-0.9497f,0.2503f,-0.2921f};
  static const f32 stdv[16]={2.8184f,1.4541f,2.3275f,2.6558f,1.2196f,1.7708f,2.6052f,2.0743f,3.2687f,2.1526f,2.8652f,1.5579f,1.6382f,1.1253f,2.8251f,1.916f};
  Image a=make_image(h,w,16);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<16;c++)a.v[((size_t)y*w+x)*16+c]=z[((size_t)c*h+y)*w+x]*stdv[c]+mean[c];
  a=conv1x1x1(a,"post_quant_conv",16);dump_image("post_quant_conv",a);
  a=conv3(a,"decoder.conv_in",384);dump_image("conv_in",a);
  a=resblock(a,"decoder.mid_block.resnets.0",384);
  dump_image("mid_resnet0",a);
  a=attention(a,"decoder.mid_block.attentions.0");
  dump_image("mid_attention",a);
  a=resblock(a,"decoder.mid_block.resnets.1",384);
  dump_image("mid_resnet1",a);
  for(int i=0;i<4;i++){
    int out=(i<2)?384:((i==2)?192:96);
    for(int r=0;r<3;r++){a=resblock(a,"decoder.up_blocks."+std::to_string(i)+".resnets."+std::to_string(r),out);dump_image(("up"+std::to_string(i)+"_resnet"+std::to_string(r)).c_str(),a);}
    if(i<3){dump_image(("up"+std::to_string(i)+"_pre_upsample").c_str(),a);int next=out/2;std::string p="decoder.up_blocks."+std::to_string(i)+".upsamplers.0.resample.1";a=upsample_conv(a,p,next);dump_image(("up"+std::to_string(i)+"_upsample").c_str(),a);}
    dump_image(("up_block"+std::to_string(i)).c_str(),a);
  }
  norm_silu(a,"decoder.norm_out");dump_image("norm_out",a);a=conv3(a,"decoder.conv_out",3);dump_image("conv_out",a);
  for(float& v:a.v)v=std::max(-1.f,std::min(1.f,v));return a;
}
static void dump_image(const char* name,const Image& x){if(!g_debug_dir)return;std::string p=std::string(g_debug_dir)+"/"+name+".f32";std::ofstream f(p,std::ios::binary);f.write((const char*)x.v.data(),x.v.size()*4);}
static bool read_exact(const char* p,void* dst,size_t n){std::ifstream f(p,std::ios::binary);return f&&bool(f.read((char*)dst,n))&&f.peek()==EOF;}
int main(int argc,char**argv){
  if(argc!=4){fprintf(stderr,"usage: krea_vae_cpu WEIGHTS.kva LATENT.f32 RGB.f32\n");return 2;}
  g_debug_dir=std::getenv("KREA_VAE_DEBUG_DIR");
  load_pack(argv[1]);std::vector<f32> z(16*32*32);if(!read_exact(argv[2],z.data(),z.size()*4)){fprintf(stderr,"invalid latent file\n");return 2;}
  auto t=Clock::now();Image out=decode(z.data(),32,32);fprintf(stderr,"Krea Qwen VAE decoded %dx%d in %.2fs\n",out.w,out.h,elapsed(t));
  std::ofstream f(argv[3],std::ios::binary);f.write((const char*)out.v.data(),out.v.size()*4);if(!f){fprintf(stderr,"write failed\n");return 2;}return 0;
}
