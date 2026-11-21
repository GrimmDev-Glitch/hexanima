// On-device Krea Turbo sampler: CPU text/time/patch projections and Euler loop,
// with the existing V73 Q8 block-chain runner, followed by the phone VAE runner.
#define KREA_TEXT_FUSE_LIBRARY
#include "krea_text_fuse_cpu.cpp"
#include <chrono>
#include <sys/wait.h>

static constexpr int HIDDEN=6144, IMG=256, TXT=32, NTOK=TXT+IMG, LATENT=16, LH=32, LW=32, PATCH=64;
static bool read_raw(const char* path,float* dst,size_t n){std::ifstream f(path,std::ios::binary);return f&&bool(f.read((char*)dst,n*4))&&f.peek()==EOF;}
static bool write_raw(const char* path,const float* src,size_t n){std::ofstream f(path,std::ios::binary);f.write((const char*)src,n*4);return bool(f);}
static void gelu_tanh(float*x,size_t n){const float c=0.7978845608028654f;for(size_t i=0;i<n;i++){float a=x[i];x[i]=0.5f*a*(1.f+tanhf(c*(a+0.044715f*a*a*a)));}}
static void rms6144(const float*x,float*y,int rows,const Weight&scale){for(int r=0;r<rows;r++){const float*a=x+(size_t)r*HIDDEN;float*b=y+(size_t)r*HIDDEN;double ss=0;for(int c=0;c<HIDDEN;c++)ss+=(double)a[c]*a[c];float inv=1.f/sqrtf((float)(ss/HIDDEN)+1e-5f);for(int c=0;c<HIDDEN;c++)b[c]=a[c]*inv*(load1(scale,c)+1.f);}}
static void make_timestep(float t,float* tvec,float* mod){
  float emb[256];for(int i=0;i<128;i++){float freq=expf(-logf(1e4f)*(float)i/128.f);float a=t*1000.f*freq;emb[i]=cosf(a);emb[i+128]=sinf(a);}
  float h[HIDDEN];linear(emb,h,1,HIDDEN,256,"tmlp.0.weight");add_bias(h,1,HIDDEN,wt("tmlp.0.bias"));gelu_tanh(h,HIDDEN);
  float h2[HIDDEN];linear(h,h2,1,HIDDEN,HIDDEN,"tmlp.2.weight");add_bias(h2,1,HIDDEN,wt("tmlp.2.bias"));
  float h3[HIDDEN];memcpy(h3,h2,HIDDEN*4);gelu_tanh(h3,HIDDEN);
  linear(h3,mod,1,6*HIDDEN,HIDDEN,"tproj.1.weight");add_bias(mod,1,6*HIDDEN,wt("tproj.1.bias"));memcpy(tvec,h2,HIDDEN*4);
}
static void patch_latent(const float* latent,float* image){
  std::vector<float> patches((size_t)IMG*PATCH);
  for(int py=0;py<16;py++)for(int px=0;px<16;px++){float*out=patches.data()+((size_t)py*16+px)*PATCH;int k=0;for(int c=0;c<LATENT;c++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)out[k++]=latent[((size_t)c*LH+py*2+dy)*LW+px*2+dx];}
  linear(patches.data(),image,IMG,HIDDEN,PATCH,"first.weight");add_bias(image,IMG,HIDDEN,wt("first.bias"));
}
static void rows_to_tiles(const float*rows,float*tiles){const int cbn=HIDDEN/32;for(int s=0;s<NTOK/32;s++)for(int cb=0;cb<cbn;cb++){float*tile=tiles+((size_t)s*cbn+cb)*1024;for(int r=0;r<32;r++)memcpy(tile+r*32,rows+(size_t)(s*32+r)*HIDDEN+cb*32,128);}}
static void tiles_to_rows(const float*tiles,float*rows){const int cbn=HIDDEN/32;for(int s=0;s<NTOK/32;s++)for(int cb=0;cb<cbn;cb++){const float*tile=tiles+((size_t)s*cbn+cb)*1024;for(int r=0;r<32;r++)memcpy(rows+(size_t)(s*32+r)*HIDDEN+cb*32,tile+r*32,128);}}
static void make_rope(float*cosv,float*sinv){for(int tk=0;tk<NTOK;tk++){int y=0,x=0;if(tk>=TXT){int q=tk-TXT;y=q/16;x=q%16;}int d0=0;for(int axis=0;axis<3;axis++){int dim=axis==0?32:48;int pos=axis==0?0:axis==1?y:x;for(int j=0;j<dim;j+=2){double angle=(double)pos*pow(1000.0,-(double)j/dim);cosv[(size_t)tk*64+d0]=(float)cos(angle);sinv[(size_t)tk*64+d0]=(float)sin(angle);d0++;}}}}
static void make_mask(int32_t*mask){for(int i=0;i<NTOK;i++)mask[i]=(i<17|| (i>=17&&i<22) || i>=TXT)?1:0;}
static void last_layer(const float*rows,const float*tvec,const float*mod,float*velocity){
  const float*modlin=(const float*)wt("last.modulation.lin").p;std::vector<float>y((size_t)IMG*HIDDEN),norm(y.size());
  for(int r=0;r<IMG;r++){const float*x=rows+(size_t)(TXT+r)*HIDDEN;float*d=y.data()+(size_t)r*HIDDEN;for(int c=0;c<HIDDEN;c++)d[c]=x[c];}
  rms6144(y.data(),norm.data(),IMG,wt("last.norm.scale"));for(int r=0;r<IMG;r++)for(int c=0;c<HIDDEN;c++){float scale=tvec[c]+modlin[c],shift=tvec[c]+modlin[HIDDEN+c];norm[(size_t)r*HIDDEN+c]=(1.f+scale)*norm[(size_t)r*HIDDEN+c]+shift;}
  std::vector<float>pred((size_t)IMG*PATCH);linear(norm.data(),pred.data(),IMG,PATCH,HIDDEN,"last.linear.weight");add_bias(pred.data(),IMG,PATCH,wt("last.linear.bias"));
  for(int py=0;py<16;py++)for(int px=0;px<16;px++)for(int c=0;c<LATENT;c++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)velocity[((size_t)c*LH+py*2+dy)*LW+px*2+dx]=pred[((size_t)py*16+px)*PATCH+(c*2+dy)*2+dx];
}
static bool call_vae(const char*vae_dir,const char*weights,const char*latent,const char*rgb){pid_t p=fork();if(p<0)return false;if(!p){if(chdir(vae_dir)){perror("chdir VAE");_exit(126);}execl("./krea_vae_cpu","krea_vae_cpu",weights,latent,rgb,(char*)nullptr);perror("exec VAE");_exit(127);}int st=0;waitpid(p,&st,0);return WIFEXITED(st)&&WEXITSTATUS(st)==0;}

int main(int argc,char**argv){
  if(argc!=9){fprintf(stderr,"usage: krea_phone_sampler WEIGHTS.kft CONTEXT.f32 NOISE.f32 OUT_LATENT.f32 Q8_DIR VAE_DIR VAE_WEIGHTS VAE_RGB.f32\n");return 2;}
  load_pack(argv[1]);std::vector<float>padded((size_t)512*HIDDEN);if(!read_raw(argv[2],padded.data(),padded.size())){fprintf(stderr,"invalid Krea context\n");return 2;}
  std::vector<float>ctx((size_t)TXT*HIDDEN,0.f);memcpy(ctx.data(),padded.data(),17*HIDDEN*4);memcpy(ctx.data()+(size_t)17*HIDDEN,padded.data()+(size_t)507*HIDDEN,5*HIDDEN*4);
  std::vector<float>latent((size_t)LATENT*LH*LW),rows((size_t)NTOK*HIDDEN),tiles(rows.size()),mod((size_t)6*HIDDEN),tvec(HIDDEN),img((size_t)IMG*HIDDEN),vel((size_t)LATENT*LH*LW),cosv((size_t)NTOK*64),sinv(cosv.size());
  if(!read_raw(argv[3],latent.data(),latent.size())){fprintf(stderr,"invalid initial noise\n");return 2;}for(float v:latent)if(!std::isfinite(v)){fprintf(stderr,"noise has non-finite values\n");return 2;}
  std::vector<int32_t>mask(NTOK);make_mask(mask.data());make_rope(cosv.data(),sinv.data());std::string dir=argv[5];
  {std::ofstream f(dir+"/cos.bin",std::ios::binary);f.write((char*)cosv.data(),cosv.size()*4);if(!f)return 3;}
  {std::ofstream f(dir+"/sin.bin",std::ios::binary);f.write((char*)sinv.data(),sinv.size()*4);if(!f)return 3;}
  {std::ofstream f(dir+"/mask.bin",std::ios::binary);f.write((char*)mask.data(),mask.size()*4);if(!f)return 3;}
  const double emu=exp(1.15);double ts[9];for(int i=0;i<=8;i++){double t=1.0-(double)i/8.0;ts[i]=i==8?0.0:emu/(emu+(1.0/t-1.0));}ts[0]=1.0;
  int run_steps=getenv("KREA_PHONE_STEPS")?std::max(1,std::min(8,atoi(getenv("KREA_PHONE_STEPS")))):8;const char* dbg=getenv("KREA_SAMPLER_DEBUG_DIR");
  for(int step=0;step<run_steps;step++){auto started=std::chrono::steady_clock::now();make_timestep((float)ts[step],tvec.data(),mod.data());patch_latent(latent.data(),img.data());memcpy(rows.data(),ctx.data(),ctx.size()*4);memcpy(rows.data()+(size_t)TXT*HIDDEN,img.data(),img.size()*4);rows_to_tiles(rows.data(),tiles.data());
    if(dbg&&step==0){std::string p=std::string(dbg)+"/";write_raw((p+"tvec.f32").c_str(),tvec.data(),tvec.size());write_raw((p+"mod.f32").c_str(),mod.data(),mod.size());write_raw((p+"image-patches.f32").c_str(),img.data(),img.size());write_raw((p+"input-tiles.f32").c_str(),tiles.data(),tiles.size());}
    if(!write_raw((dir+"/x32.bin").c_str(),tiles.data(),tiles.size())||!write_raw((dir+"/mod.bin").c_str(),mod.data(),mod.size()))return 3;
    if(chdir(dir.c_str())){perror("chdir Q8");return 3;}int rc=system("LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./krea_blk_smoke . 288 288 0 3 72 chain > krea-phone-step.log 2>&1");if(rc!=0){fprintf(stderr,"Q8 chain failed at step %d (rc=%d)\n",step+1,rc);return 4;}
    std::vector<float>tiled(rows.size());if(!read_raw("x32_out.bin",tiled.data(),tiled.size())){fprintf(stderr,"missing Q8 output at step %d\n",step+1);return 4;}tiles_to_rows(tiled.data(),rows.data());last_layer(rows.data(),tvec.data(),mod.data(),vel.data());double rms=0;for(size_t i=0;i<latent.size();i++){latent[i]+=(float)(ts[step+1]-ts[step])*vel[i];rms+=(double)latent[i]*latent[i];}if(dbg&&step==0){std::string p=std::string(dbg)+"/";write_raw((p+"out-rows.f32").c_str(),rows.data(),rows.size());write_raw((p+"velocity.f32").c_str(),vel.data(),vel.size());write_raw((p+"latent-step1.f32").c_str(),latent.data(),latent.size());}fprintf(stderr,"Krea phone step %d/8 done in %.2fs latent_rms=%g\n",step+1,std::chrono::duration<float>(std::chrono::steady_clock::now()-started).count(),sqrt(rms/latent.size()));
  }
  if(!write_raw(argv[4],latent.data(),latent.size())){perror("write latent");return 5;}for(float v:latent)if(!std::isfinite(v)){fprintf(stderr,"final latent contains non-finite values\n");return 5;}
  if(!getenv("KREA_SKIP_VAE")){if(!call_vae(argv[6],argv[7],argv[4],argv[8])){fprintf(stderr,"phone VAE decode failed\n");return 6;}fprintf(stderr,"Krea phone sampler and VAE completed; RGB float output: %s\n",argv[8]);}else fprintf(stderr,"Krea phone sampler completed; VAE skipped by diagnostic setting\n");return 0;
}
