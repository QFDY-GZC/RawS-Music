#include "raw_algorithmic_reverb.h"
#include <algorithm>
#include <cmath>
namespace rawsmusic::dsp {
namespace {
float bounded(float value, float low, float high, float fallback) {
    return std::isfinite(value) ? std::clamp(value,low,high) : fallback;
}
ReverbParams sanitize(ReverbParams p) {
    p.space=std::clamp(p.space,0,4); p.mix=bounded(p.mix,0,1,.15f);
    p.size=bounded(p.size,0,1,.35f); p.decay=bounded(p.decay,.15f,12,1.2f);
    p.preDelay=bounded(p.preDelay,0,200,12); p.damping=bounded(p.damping,0,1,.45f);
    p.diffusion=bounded(p.diffusion,0,1,.7f); p.width=bounded(p.width,0,1.5f,1);
    p.lowCut=bounded(p.lowCut,20,1000,100); p.highCut=bounded(p.highCut,1000,20000,12000);
    p.earlyLate=bounded(p.earlyLate,0,1,.65f); p.gainDb=bounded(p.gainDb,-12,6,0); return p;
}
constexpr float pi=3.14159265358979323846f;
}
void AlgorithmicReverb::Delay::init(size_t capacity) { data.assign(capacity,0); position=0; }
void AlgorithmicReverb::Delay::clear() { std::fill(data.begin(),data.end(),0); position=0; }
void AlgorithmicReverb::Delay::push(float value) { data[position]=value; if(++position==data.size())position=0; }
float AlgorithmicReverb::Delay::read(float frames) const {
    frames=std::clamp(frames,0.f,static_cast<float>(data.size()-2));
    const auto whole=static_cast<size_t>(frames); const float fraction=frames-whole;
    const auto a=(position+data.size()-1-whole)%data.size();
    const auto b=(a+data.size()-1)%data.size(); return data[a]+fraction*(data[b]-data[a]);
}
void AlgorithmicReverb::Bank::init(int sampleRate) {
    rate=sampleRate; for(auto& d:fdn)d.init(static_cast<size_t>(rate*.24f)+4);
    for(auto& d:diffuser)d.init(static_cast<size_t>(rate*.02f)+4);
    for(auto& d:pre)d.init(static_cast<size_t>(rate*.5f)+4); configure(params);
}
void AlgorithmicReverb::Bank::clear() {
    for(auto& d:fdn)d.clear(); for(auto& d:diffuser)d.clear(); for(auto& d:pre)d.clear();
    dampState.fill(0); hpInput.fill(0); hpOutput.fill(0); lpOutput.fill(0);
}
void AlgorithmicReverb::Bank::copyState(const Bank& other) {
    auto copy=[](Delay& dest,const Delay& src){std::copy(src.data.begin(),src.data.end(),dest.data.begin()); dest.position=src.position;};
    for(size_t i=0;i<8;++i){copy(fdn[i],other.fdn[i]);copy(diffuser[i],other.diffuser[i]);}
    for(size_t i=0;i<2;++i)copy(pre[i],other.pre[i]);
    dampState=other.dampState; hpInput=other.hpInput; hpOutput=other.hpOutput; lpOutput=other.lpOutput;
}
void AlgorithmicReverb::Bank::configure(ReverbParams p) {
    params=sanitize(p);
    constexpr float times[8]={.0297f,.0371f,.0411f,.0437f,.0533f,.0617f,.0719f,.0893f};
    const float scale=(.5f+params.size*1.3f)*(1.f+params.space*.05f);
    for(size_t i=0;i<8;++i){length[i]=times[i]*rate*scale; feedback[i]=std::pow(10.f,-3.f*length[i]/rate/params.decay);}
    const float nyquist=rate*.45f;
    hp=std::exp(-2*pi*std::min(params.lowCut,nyquist)/rate);
    lp=std::exp(-2*pi*std::min(params.highCut,nyquist)/rate);
    damping=std::exp(-2*pi*std::min(18000.f*std::pow(1200.f/18000.f,params.damping),nyquist)/rate);
    gain=std::pow(10.f,params.gainDb/20.f);
    // Equal-power mix avoids the linear mix's audible level dip. Compute once per update.
    dryMix=params.mix>=1 ? 0.f : std::cos(params.mix*pi*.5f);
    wetMix=std::sin(params.mix*pi*.5f);
}
std::array<float,2> AlgorithmicReverb::Bank::tick(float left,float right) {
    std::array<float,2> input{left,right}, early{}, diffuse{};
    constexpr float taps[4]={.009f,.017f,.029f,.047f};
    for(int ch=0;ch<2;++ch){
        const float highPassed=hp*(hpOutput[ch]+input[ch]-hpInput[ch]);
        hpInput[ch]=input[ch]; hpOutput[ch]=highPassed;
        lpOutput[ch]=lp*lpOutput[ch]+(1-lp)*highPassed; pre[ch].push(lpOutput[ch]);
        const float preFrames=params.preDelay*rate/1000;
        for(int t=0;t<4;++t)early[ch]+=pre[ch].read(preFrames+taps[t]*rate*(.4f+params.size*1.6f)*(1+ch*.13f+params.space*.04f))*(.22f-t*.035f);
        float value=pre[ch].read(preFrames);
        for(int t=0;t<4;++t){auto& d=diffuser[ch*4+t];
            const float delayed=d.read((.0031f+t*.002f+ch*.00043f)*rate);
            const float g=.15f+params.diffusion*.6f;
            const float out=delayed-g*value; d.push(value+g*out); value=out;
        }
        diffuse[ch]=value;
    }
    std::array<float,8> values{}, matrix{};
    for(int i=0;i<8;++i){values[i]=fdn[i].read(length[i]);
        dampState[i]=damping*dampState[i]+(1-damping)*values[i];
        if(std::abs(dampState[i])<1e-20f)dampState[i]=0;
        matrix[i]=dampState[i];}
    // Normalized Hadamard feedback matrix: unit energy, loop gain always below one.
    for(int stride=1;stride<8;stride*=2)for(int group=0;group<8;group+=stride*2)for(int j=0;j<stride;++j){
        const float a=matrix[group+j],b=matrix[group+j+stride]; matrix[group+j]=a+b; matrix[group+j+stride]=a-b;}
    for(int i=0;i<8;++i)fdn[i].push(matrix[i]*.35355339f*feedback[i]+diffuse[i%2]*(i<4?.18f:-.18f));
    float lateL=(values[0]+values[2]-values[4]+values[6])*.35f;
    float lateR=(values[1]+values[3]+values[5]-values[7])*.35f;
    // Calibrated against a band-limited probe: the raw network is ~17 dB below dry.
    // Restore 12 dB on the output only; never increase the feedback loop gain.
    constexpr float wetCalibration=4.f;
    float l=(early[0]*(1-params.earlyLate)+lateL*params.earlyLate)*gain*wetCalibration;
    float r=(early[1]*(1-params.earlyLate)+lateR*params.earlyLate)*gain*wetCalibration;
    const float mid=(l+r)*.5f, side=(l-r)*.5f*params.width, norm=1/std::max(1.f,params.width);
    return {(mid+side)*norm,(mid-side)*norm};
}
void AlgorithmicReverb::init(int sampleRate) {
    rate_=std::clamp(sampleRate,8000,768000); fadeTotal_=std::max(1,rate_/20);
    for(auto& bank:banks_)bank.init(rate_); active_=0; activation_=0; appliedVersion_=0; fadeRemaining_=0;
}
void AlgorithmicReverb::setParams(const ReverbParams& p) { std::lock_guard<std::mutex> lock(mailboxMutex_);pending_=sanitize(p);++version_; }
void AlgorithmicReverb::setEnabled(bool enabled) { enabled_.store(enabled); if(enabled)fading_.store(true); }
bool AlgorithmicReverb::isActive() const { return enabled_.load()||fading_.load(); }
void AlgorithmicReverb::reset() { resetRequested_.store(true); }
void AlgorithmicReverb::process(float* samples,int frames,int channels) {
    if(!samples||frames<=0||channels<1||channels>32)return;
    if(resetRequested_.exchange(false)){for(auto& bank:banks_)bank.clear(); activation_=0;}
    if(fadeRemaining_==0 && mailboxMutex_.try_lock()){
        if(appliedVersion_!=version_){const int next=1-active_; banks_[next].copyState(banks_[active_]);
            banks_[next].configure(pending_); appliedVersion_=version_; fadeRemaining_=fadeTotal_;}
        mailboxMutex_.unlock();
    }
    if(!isActive())return;
    const float target=enabled_.load()?1.f:0.f, step=1.f/fadeTotal_;
    for(int frame=0;frame<frames;++frame){
        activation_=std::clamp(activation_+(target>activation_?step:target<activation_?-step:0.f),0.f,1.f);
        const float l=samples[frame*channels], r=channels>1?samples[frame*channels+1]:l;
        auto wet=banks_[active_].tick(l,r);
        float dryMix=banks_[active_].dryMix, wetMix=banks_[active_].wetMix;
        if(fadeRemaining_>0){auto next=banks_[1-active_].tick(l,r); const float alpha=1-float(fadeRemaining_)/fadeTotal_;
            const auto& nextBank=banks_[1-active_];
            wet[0]=wet[0]*wetMix*(1-alpha)+next[0]*nextBank.wetMix*alpha;
            wet[1]=wet[1]*wetMix*(1-alpha)+next[1]*nextBank.wetMix*alpha;
            dryMix=dryMix*(1-alpha)+nextBank.dryMix*alpha;
            if(--fadeRemaining_==0)active_=1-active_;
        }else{wet[0]*=wetMix;wet[1]*=wetMix;}
        const float dry=1+(dryMix-1)*activation_;
        if(channels==1)samples[frame]=l*dry+(wet[0]+wet[1])*.5f*activation_;
        else{samples[frame*channels]=l*dry+wet[0]*activation_; samples[frame*channels+1]=r*dry+wet[1]*activation_;}
    }
    fading_.store(activation_>0);
    if(!enabled_.load()&&activation_==0){for(auto& bank:banks_)bank.clear();}
}
}
