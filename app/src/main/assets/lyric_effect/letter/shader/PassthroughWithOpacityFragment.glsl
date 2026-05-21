//#version 410 core

precision highp float;
varying vec2 vTexCoord;

uniform sampler2D sDiffMap;
uniform float cOpacity;

void main()
{
    vec4 diffInput = texture2D(sDiffMap, vTexCoord);
    gl_FragColor = diffInput * cOpacity;
}
