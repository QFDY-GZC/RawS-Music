#include "raw_algorithmic_reverb.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <chrono>
#include <limits>
using namespace rawsmusic::dsp;
double energy(const std::vector<float>& b, int from = 0) { double e=0; for(size_t i=from;i<b.size();++i)e+=b[i]*b[i]; return e; }
int main() {
    // A band-limited, repeatable music-like probe measures wet level separately from dry.
    for(float decay : {.55f, 2.8f, 5.5f}) {
        AlgorithmicReverb probe; probe.init(48000);
        ReverbParams q; q.mix=1; q.size=.75f; q.decay=decay;
        q.preDelay=28; q.damping=.55f; q.diffusion=.85f; q.earlyLate=.75f;
        probe.setParams(q); probe.setEnabled(true);
        std::vector<float> b(256*2); double dryEnergy=0, wetEnergy=0;
        unsigned seed=12345; float filtered=0;
        for(int k=0;k<48000*10/256;++k) {
            for(int i=0;i<256;++i) {
                seed=1664525u*seed+1013904223u;
                const float noise=(float(seed>>8)/16777216.f-.5f)*.3f;
                filtered=.7f*filtered+.3f*noise;
                b[i*2]=b[i*2+1]=filtered;
                if(k>48000*5/256)dryEnergy+=2*filtered*filtered;
            }
            probe.process(b.data(),256,2);
            if(k>48000*5/256)wetEnergy+=energy(b);
        }
        std::printf("REVERB_WET_LEVEL decay=%.2f ratio=%.4f db=%.2f\n",decay,
            std::sqrt(wetEnergy/dryEnergy),10*std::log10(wetEnergy/dryEnergy));
        assert(wetEnergy/dryEnergy>.12 && wetEnergy/dryEnergy<1);
    }
    AlgorithmicReverb r; r.init(48000);
    ReverbParams p; p.mix=1; p.preDelay=0; p.decay=2;
    r.setParams(p); r.setEnabled(true);
    std::vector<float> warm(4800*2); r.process(warm.data(),4800,2);
    std::vector<float> impulse(48000*3*2); impulse[0]=1; r.process(impulse.data(),48000*3,2);
    assert(energy(impulse, 4800*2)>0.000001);
    double difference=0; for(size_t i=0;i<impulse.size();i+=2)difference+=std::abs(impulse[i]-impulse[i+1]);
    assert(difference>0.001);
    // A longer RT60 must preserve more late energy than a short room.
    auto tailEnergy=[](float decay){AlgorithmicReverb effect;effect.init(48000);
        ReverbParams q;q.mix=1;q.preDelay=0;q.decay=decay;effect.setParams(q);effect.setEnabled(true);
        std::vector<float> zero(9600);effect.process(zero.data(),4800,2);
        std::vector<float> signal(48000*3*2);signal[0]=1;effect.process(signal.data(),48000*3,2);
        return energy(signal,48000*2);};
    assert(tailEnergy(4)>tailEnergy(.4f)*5);
    AlgorithmicReverb blend; blend.init(48000);
    ReverbParams blendParams; blendParams.mix=.4f; blendParams.preDelay=200;
    blend.setParams(blendParams); blend.setEnabled(true);
    std::vector<float> blendWarm(9600); blend.process(blendWarm.data(),4800,2);
    float dryProbe[2]={.25f,-.25f}; blend.process(dryProbe,1,2);
    assert(std::abs(dryProbe[0]-.25f*std::cos(.4f*3.14159265359f*.5f))<1e-6);
    assert(std::abs(dryProbe[1]+dryProbe[0])<1e-6);
    AlgorithmicReverb mono;mono.init(48000);p.mix=.4f;mono.setParams(p);mono.setEnabled(true);
    std::vector<float> multichannel(4800*4,.1f);mono.process(multichannel.data(),4800,4);
    for(size_t i=0;i<multichannel.size();i+=4){assert(multichannel[i+2]==.1f);assert(multichannel[i+3]==.1f);}
    r.setEnabled(false); r.process(warm.data(),4800,2);
    std::vector<float> dry{.3f,-.4f,.1f,.7f}; auto expected=dry;
    r.process(dry.data(),2,2); assert(dry==expected);
    r.setEnabled(true); p.mix=0; r.setParams(p); r.process(warm.data(),4800,2);
    r.process(dry.data(),2,2); assert(dry==expected);
    p.mix=1; r.setParams(p); r.process(warm.data(),4800,2); r.reset();
    std::vector<float> silence(48000*2); r.process(silence.data(),48000,2); assert(energy(silence)==0);
    for(int rate : {44100,48000,96000,192000,384000}) {
        AlgorithmicReverb high; high.init(rate); p.mix=.5f; p.decay=12; p.size=1;
        high.setParams(p); high.setEnabled(true);
        std::vector<float> block(256*2,.2f);
        const auto start=std::chrono::steady_clock::now();
        for(int k=0;k<rate/256;++k) { std::fill(block.begin(),block.end(),.2f); high.process(block.data(),256,2); for(float v:block)assert(std::isfinite(v)&&std::abs(v)<2); }
        auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
        std::printf("REVERB_RATE=%d oneSecondCpuMs=%lld\n",rate,(long long)ms);
    }
    std::puts("REVERB_IMPULSE_STEREO_BYPASS_ZERO_MIX_RESET_STABILITY=true");
}
