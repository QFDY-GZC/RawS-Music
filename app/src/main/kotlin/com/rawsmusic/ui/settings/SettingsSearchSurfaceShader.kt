package com.rawsmusic.ui.settings

/** Type-4 capsule and connected material math, expressed through public AGSL. */
internal object SettingsSearchSurfaceShader {
    const val source = """
        uniform shader background;
        uniform float first[8];
        uniform float second[8];
        uniform float connection[24];
        uniform float2 quadOrigin;
        uniform float viewportHeight;
        uniform float cancelOpacity;
        uniform float edgeWidth;
        uniform float edgeAlpha;
        uniform float fadeAlpha;
        uniform float fadeWidth;
        uniform float searchDecoration;
        const float EPS = 0.000001;
        struct Geometry { float d; float a; float b; float aa; float aaB; };
        float gain(float x, float k) {
            float a = 0.5 * pow(2.0 * (x < 0.5 ? x : 1.0-x), k);
            return x < 0.5 ? a : 1.0-a;
        }
        float capsule(float2 p, float2 halfSize, inout float aa) {
            bool vertical = halfSize.x < halfSize.y;
            float2 hs = vertical ? halfSize.yx : halfSize;
            float2 pl = vertical ? p.yx : p;
            float2 q = sqrt(pl*pl + 1.0);
            float a = hs.x-hs.y;
            float s = q.x-a;
            float k = 26.6 * hs.y / 80.0;
            float xSoft = 0.5 * (s + sqrt(s*s+k*k));
            float wx = 1.0-smoothstep(0.0,max(70.0*hs.y/80.0,0.001),abs(s));
            float wy = smoothstep(hs.y-max(60.0*hs.y/80.0,0.001),hs.y,q.y);
            float x = mix(max(s,0.0),xSoft,clamp(wx*wy,0.0,1.0));
            float transition = max(70.0*hs.y/80.0,4.0);
            aa = smoothstep(-transition-2.0,-transition*0.2,abs(pl.x)-a);
            return length(float2(x,q.y))-hs.y;
        }
        float rbox(float2 p,float2 b,float r) {
            float2 q=abs(p)-b+r;
            return min(max(q.x,q.y),0.0)+length(max(q,0.0))-r;
        }
        Geometry geometry(float2 position) {
            float2 p=float2(position.x,viewportHeight-position.y);
            float2 pa=(p-float2(first[0],first[1]))*float2(first[6],first[7]);
            float2 pb=(p-float2(second[0],second[1]))*float2(second[6],second[7]);
            float aa1=0.0,aa2=0.0;
            float da=capsule(pa,float2(first[2],first[3]),aa1);
            float db=cancelOpacity>EPS && second[2]>=1.0 && second[3]>=1.0 ?
                capsule(pb,float2(second[2],second[3]),aa2) : 100000.0;
            float aa=aa1,aaB=aa2;
            float rawA=da,rawB=db;
            float shadeA=da,shadeB=db;
            float blended=min(da,db);
            if(connection[21]>0.5 && cancelOpacity>EPS) {
                float2 dir=float2(connection[2],connection[3]);
                float2 delta=p-float2(connection[0],connection[1]);
                float dy=abs(dot(delta,dir));
                float dx=abs(dot(delta,float2(dir.y,-dir.x)));
                if(connection[20]>EPS) {
                    float ra=smoothstep(connection[9],0.0,dy)*
                        gain(smoothstep(connection[10],0.0,dx),connection[15]);
                    float rb=smoothstep(connection[12],0.0,dy)*
                        gain(smoothstep(connection[13],0.0,dx),connection[15]);
                    if(ra>EPS) {
                        pa-=dir*connection[11]*ra*float2(first[6],first[7]);
                        da=capsule(pa,float2(first[2],first[3]),aa1);
                        aa=max(aa,ra);
                        aaB=max(aaB,ra);
                    }
                    if(rb>EPS) {
                        pb+=dir*connection[14]*rb*float2(second[6],second[7]);
                        db=capsule(pb,float2(second[2],second[3]),aa2);
                        aa=max(aa,rb);
                        aaB=max(aaB,rb);
                    }
                }
                float sd=rbox(p-float2(connection[4],connection[5]),
                    float2(connection[6],connection[7]),connection[8]);
                float mask=1.0-smoothstep(50.0,0.0,abs(sd))*connection[16];
                float strength=connection[17]*mask;
                if(strength>EPS) {
                    float h=clamp(0.5+0.5*(da-db)/strength,0.0,1.0);
                    blended=mix(da,db,h)-strength*h*(1.0-h);
                    aa=max(max(aa,h*(1.0-h)*4.0),1.0);
                    aaB=max(max(aaB,h*(1.0-h)*4.0),1.0);
                    float handoff=smoothstep(0.0,strength,db-da-strength);
                    shadeB=min(rawB,blended*(1.0-handoff)+db*handoff);
                } else blended=min(da,db);
                shadeA=min(rawA,da);
            }
            Geometry g;
            g.d=min(min(rawA,rawB),blended);g.a=shadeA;g.b=shadeB;g.aa=aa;g.aaB=aaB;
            return g;
        }
        // Public AGSL has no derivatives. Evaluate the same SDF at the framebuffer
        // quad's pixel centres, rather than substituting a constant feather width.
        float footprint(float2 p) {
            float2 screen=p+quadOrigin;
            float2 base=floor(screen*0.5)*2.0+0.5-quadOrigin;
            float2 lane=floor(screen)-floor(screen*0.5)*2.0;
            float dx=geometry(base+float2(1.0,lane.y)).d-geometry(base+float2(0.0,lane.y)).d;
            float dy=geometry(base+float2(lane.x,1.0)).d-geometry(base+float2(lane.x,0.0)).d;
            return abs(dx)+abs(dy);
        }
        float noise(float2 st) { return fract(sin(dot(st.yx,float2(79.1214,78.233)))*43760.5453); }
        float softLight(float a,float b) {
            return mix(b-(1.0-2.0*a)*b*(1.0-b),b+(2.0*a-1.0)*(sqrt(b)-b),step(0.5,a));
        }
        float3 material(float2 coords,float2 pos,float2 hs,float d,float3 raw) {
            float gray=clamp((dot(float3(0.2126,0.7152,0.0722),raw)-0.6)*1.6,0.0,1.0);
            float af=0.0;
            float ignored=0.0;
            float fadeSdf=capsule(pos-float2(0.0,20.0),hs+float2(120.0,15.0),ignored);
            float fadeLine=-120.0*fadeWidth;
            float fade=smoothstep(fadeLine,0.0,fadeSdf)*smoothstep(fadeLine,0.0,d);
            af=(pow(fade,4.0)*0.9+0.1*fade)*fadeAlpha*(1.0-gray)*(1.0-gray);
            if(af>EPS) af=clamp(softLight(clamp((noise(coords*0.000925)-0.5)*0.008+0.5,0.0,1.0),af),0.0,1.0);
            float3 result=clamp(raw+af,0.0,1.0);
            if(d>=-edgeWidth) {
                float2 dir=pos/max(length(pos),EPS);
                float2 hit=min(abs(dir.xy)*hs.yx/max(float2(0.001),abs(dir.yx)),hs)*sign(dir);
                float perimeter=max(2.0*(hs.x+hs.y),1.0);
                float phase=fract(0.5+0.5*hs.x/perimeter);
                float point=2.0*perimeter*min(phase,1.0-phase);
                float flag=(phase<=0.5?1.0:-1.0)*dot(float2(-hs.y,hs.x),hit);
                float pathDistance=abs(hit.x-hs.x)+abs(hit.y-hs.y);
                float near=mix(point+pathDistance,abs(point-pathDistance),step(0.0,flag));
                near=min(2.0*perimeter-near,near);
                float n=smoothstep(-edgeWidth,-edgeWidth*0.2,d);
                float f=smoothstep(-edgeWidth*0.5,-edgeWidth*0.2,d);
                n=pow(n,4.0)*0.8+0.2*n; f=pow(f,4.0)*0.8+0.2*f;
                float alpha=clamp(edgeAlpha*(0.7*smoothstep(hs.x-200.0,hs.x+150.0,near)*n+
                    smoothstep(hs.x-250.0,hs.x+100.0,perimeter-near)*f),0.0,1.0);
                float lum=clamp(dot(float3(0.2126,0.7152,0.0722),raw)*1.2,0.0,1.0);
                float improve=pow(1.0-lum,4.0)*0.1;
                result=mix(result,2.0*clamp(raw+improve,0.0,1.0),alpha);
            }
            return clamp(result,0.0,1.0);
        }
        half4 main(float2 p) {
            Geometry g=geometry(p);
            float width=EPS;
            if(abs(g.d)<4.0 && max(g.aa,g.aaB)>EPS) width=footprint(p);
            float aa=max(EPS,mix(EPS,width,g.aa));
            float aaB=max(EPS,mix(EPS,width,g.aaB));
            float cover=smoothstep(aa,-aa,g.d);
            float coverB=smoothstep(aaB,-aaB,g.d);
            if(max(cover,coverB)<EPS) return half4(0.0);
            half4 sampledBackground=background.eval(p);
            float3 raw=clamp(float3(sampledBackground.rgb)/max(float(sampledBackground.a),EPS),0.0,1.0);
            float2 coords=float2(p.x,viewportHeight-p.y);
            float range=max(50.0*connection[19],2.0+aa);
            float a=cover;
            float b=0.0;
            if(cancelOpacity>EPS) {
                a=pow(smoothstep(range,aa,g.a),0.883)*cover;
                float rangeB=max(50.0*connection[19],2.0+aaB);
                b=pow(smoothstep(rangeB,aaB,g.b),0.883)*step(aaB,g.a)*coverB*cancelOpacity;
            }
            float3 colA=searchDecoration>0.5 ? material(coords,
                (coords-float2(first[0],first[1]))*float2(first[6],first[7]),
                float2(first[2],first[3]),g.d,raw) : raw;
            float3 colB=material(coords,(coords-float2(second[0],second[1]))*float2(second[6],second[7]),
                float2(second[2],second[3]),g.d,raw);
            float alpha=b+a*(1.0-b);
            return half4((colB*b+colA*a*(1.0-b))*sampledBackground.a,alpha*sampledBackground.a);
        }
    """
}
