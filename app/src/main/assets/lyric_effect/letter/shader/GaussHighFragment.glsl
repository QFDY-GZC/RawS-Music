precision highp float;

uniform sampler2D sDiffMap;
uniform float cTexelWidthOffset;
uniform float cTexelHeightOffset;

uniform vec4 cWeight0;
uniform vec4 cWeight1;
uniform vec4 cWeight2;
uniform vec4 cWeight3;
uniform vec4 cAddOffest0;
uniform vec4 cAddOffest1;

varying highp vec2 blurCoordinates[15];

void main()
{
    lowp vec4 sum = vec4(0.0);
    sum += texture2D(sDiffMap, blurCoordinates[0]) * cWeight1.w;
    sum += texture2D(sDiffMap, blurCoordinates[1]) * cWeight0.x;
    sum += texture2D(sDiffMap, blurCoordinates[2]) * cWeight0.x;
    sum += texture2D(sDiffMap, blurCoordinates[3]) * cWeight0.y;
    sum += texture2D(sDiffMap, blurCoordinates[4]) * cWeight0.y;
    
    //if (cWeight0.z > 0.00001) {
        sum += texture2D(sDiffMap, blurCoordinates[5]) * cWeight0.z;
        sum += texture2D(sDiffMap, blurCoordinates[6]) * cWeight0.z;
        sum += texture2D(sDiffMap, blurCoordinates[7]) * cWeight0.w;
        sum += texture2D(sDiffMap, blurCoordinates[8]) * cWeight0.w;
        sum += texture2D(sDiffMap, blurCoordinates[9]) * cWeight1.x;
        sum += texture2D(sDiffMap, blurCoordinates[10]) * cWeight1.x;
        sum += texture2D(sDiffMap, blurCoordinates[11]) * cWeight1.y;
        sum += texture2D(sDiffMap, blurCoordinates[12]) * cWeight1.y;
        sum += texture2D(sDiffMap, blurCoordinates[13]) * cWeight1.z;
        sum += texture2D(sDiffMap, blurCoordinates[14]) * cWeight1.z;
    //}
    
    //if (cWeight2.x > 0.00001) {
        highp vec2 singleStepOffset = vec2(cTexelWidthOffset, cTexelHeightOffset);
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest0.x) * cWeight2.x;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest0.x) * cWeight2.x;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest0.y) * cWeight2.y;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest0.y) * cWeight2.y;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest0.z) * cWeight2.z;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest0.z) * cWeight2.z;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest0.w) * cWeight2.w;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest0.w) * cWeight2.w;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest1.x) * cWeight3.x;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest1.x) * cWeight3.x;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest1.y) * cWeight3.y;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest1.y) * cWeight3.y;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest1.z) * cWeight3.z;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest1.z) * cWeight3.z;
        sum += texture2D(sDiffMap, blurCoordinates[0] + singleStepOffset * cAddOffest1.w) * cWeight3.w;
        sum += texture2D(sDiffMap, blurCoordinates[0] - singleStepOffset * cAddOffest1.w) * cWeight3.w;
    //}
    
    gl_FragColor = sum;
}
