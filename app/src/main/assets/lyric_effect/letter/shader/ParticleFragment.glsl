//#version 410 core
precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;
varying vec2 vAlphaTestCoord;
uniform vec4 cColor;

void main()
{
    float r = distance(gl_PointCoord, vec2(0.5, 0.5));
    if (r > 0.5) {
        discard;
    }

    float c1 = 1.0 - mix(0.0, 1.0, r * 8.0);
    c1 = c1 * vColor.a;

    gl_FragColor = vec4(c1, c1, c1, c1) * cColor;
    
    //gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
}
