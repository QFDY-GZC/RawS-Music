//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform vec4 cParam1;  //cParam1.xyz:topColor, cParam1.w:maskStrength1
uniform vec4 cParam2;  //cParam2.xyz:bottomColor

void main()
{
    vec3 color = cParam1.xyz * vTexCoord.y + cParam2.xyz * (1.0 - vTexCoord.y);
    gl_FragColor = vec4(color, cParam1.w);
}
