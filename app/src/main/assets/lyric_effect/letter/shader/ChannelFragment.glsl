//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform sampler2D sDiffMap;
uniform float cChannel;

void main()
{
    float gray = 0.0;
    if (cChannel < 0.9) {
        gray = texture2D(sDiffMap, vTexCoord).r;
    }
    else if (cChannel < 1.9) {
        gray = texture2D(sDiffMap, vTexCoord).g;
    }
    else if (cChannel < 2.9) {
        gray = texture2D(sDiffMap, vTexCoord).b;
    }
    else {
        gray = texture2D(sDiffMap, vTexCoord).a;
    }
    
    gl_FragColor = vec4(gray) * vColor;
}

