//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform vec4 cParam1;  //cParam1.xyz:topColor, cParam1.w:maskStrength1

void main()
{
    gl_FragColor = cParam1;
}
