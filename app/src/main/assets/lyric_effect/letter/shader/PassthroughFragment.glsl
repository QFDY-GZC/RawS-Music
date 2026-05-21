//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform sampler2D sDiffMap;

void main()
{
    vec4 diffInput = texture2D(sDiffMap, vTexCoord);
    gl_FragColor = vec4(diffInput.x, diffInput.y, diffInput.z, diffInput.w);
}
