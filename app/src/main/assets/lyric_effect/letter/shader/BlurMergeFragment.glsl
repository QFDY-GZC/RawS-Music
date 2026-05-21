//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform sampler2D sDiffMap;
uniform sampler2D sDiffMap2;

void main()
{
    vec4 diffInput = texture2D(sDiffMap, vTexCoord);
    vec4 diffInput2 = texture2D(sDiffMap2, vTexCoord);
    gl_FragColor = diffInput * diffInput.a + diffInput2 * (1.0 - diffInput.a);
    gl_FragColor = gl_FragColor * vColor;
}
