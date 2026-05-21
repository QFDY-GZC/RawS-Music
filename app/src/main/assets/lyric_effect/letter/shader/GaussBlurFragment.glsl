//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;
uniform sampler2D sDiffMap;

uniform float cTexelWidthOffset;
uniform float cTexelHeightOffset;
uniform vec4 cColor;

vec4 doBlur(float blurLevel)
{
    float offsetArray[8];
    float weightArray[8];
    int   blurIter = 0;
    
    if (blurLevel < 0.1) {
        return texture2D(sDiffMap, vTexCoord) * vColor.a;
    }
    else if (blurLevel > 0.1 && blurLevel < 1.1) {
        blurIter = 2;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.182425;
        offsetArray[2] = 3.475713;

        weightArray[0] = 0.398943;
        weightArray[1] = 0.295963;
        weightArray[2] = 0.004566;
    }
    else if (blurLevel > 1.1 && blurLevel < 2.1) {
        blurIter = 2;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.407333;
        offsetArray[2] = 3.294215;

        weightArray[0] = 0.204164;
        weightArray[1] = 0.304005;
        weightArray[2] = 0.093913;
    }
    else if (blurLevel > 2.1 && blurLevel < 3.1) {
        blurIter = 4;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.458430;
        offsetArray[2] = 3.403985;
        offsetArray[3] = 5.351806;
        offsetArray[4] = 7.302940;

        weightArray[0] = 0.133571;
        weightArray[1] = 0.233308;
        weightArray[2] = 0.135928;
        weightArray[3] = 0.051383;
        weightArray[4] = 0.012595;
    }
    else if (blurLevel > 3.1 && blurLevel < 4.1) {
        blurIter = 5;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.476580;
        offsetArray[2] = 3.445529;
        offsetArray[3] = 5.414899;
        offsetArray[4] = 7.384912;
        offsetArray[5] = 9.355775;

        weightArray[0] = 0.100590;
        weightArray[1] = 0.186265;
        weightArray[2] = 0.136940;
        weightArray[3] = 0.078710;
        weightArray[4] = 0.035367;
        weightArray[5] = 0.012422;
    }
    else if (blurLevel > 4.1 && blurLevel < 5.1) {
        blurIter = 6;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.485004;
        offsetArray[2] = 3.465057;
        offsetArray[3] = 5.445220;
        offsetArray[4] = 7.425558;
        offsetArray[5] = 9.406127;
        offsetArray[6] = 11.386986;

        weightArray[0] = 0.080780;
        weightArray[1] = 0.153750;
        weightArray[2] = 0.126131;
        weightArray[3] = 0.088315;
        weightArray[4] = 0.052777;
        weightArray[5] = 0.026919;
        weightArray[6] = 0.011718;
    }
    else {
        blurIter = 7;
        
        offsetArray[0] = 0.0;
        offsetArray[1] = 1.489585;
        offsetArray[2] = 3.475713;
        offsetArray[3] = 5.461879;
        offsetArray[4] = 7.448104;
        offsetArray[5] = 9.434408;
        offsetArray[6] = 11.420812;
        offsetArray[7] = 13.407332;
    
        weightArray[0] = 0.067540;
        weightArray[1] = 0.130499;
        weightArray[2] = 0.113686;
        weightArray[3] = 0.088692;
        weightArray[4] = 0.061965;
        weightArray[5] = 0.038768;
        weightArray[6] = 0.021721;
        weightArray[7] = 0.010898;
    }
    
    vec2 singleStepOffset = vec2(cTexelWidthOffset, cTexelHeightOffset);
    
    vec4 sum = vec4(0.0);
    for (int i = 1; i <= blurIter; i++) {
        vec2 blurCoordinate = singleStepOffset * offsetArray[i];
        sum += texture2D(sDiffMap, vTexCoord + blurCoordinate) * weightArray[i];
    }
    for (int i = 1; i <= blurIter; i++) {
        vec2 blurCoordinate = -1.0 * singleStepOffset * offsetArray[i];
        sum += texture2D(sDiffMap, vTexCoord + blurCoordinate) * weightArray[i];
    }
    sum += texture2D(sDiffMap, vTexCoord) * weightArray[0];
    
    return sum;
}

//void setBlurLevel_1()
//{
//    blurIter = 2;
//
//    offsetArray[0] = 0.0;
//    offsetArray[1] = 1.182425;
//    offsetArray[2] = 3.475713;
//
//    weightArray[0] = 0.398943;
//    weightArray[1] = 0.295963;
//    weightArray[2] = 0.004566;
//}
//
//void setBlurLevel_2()
//{
//    blurIter = 2;
//
//    offsetArray[0] = 0.0;
//    offsetArray[1] = 1.407333;
//    offsetArray[2] = 3.294215;
//
//    weightArray[0] = 0.204164;
//    weightArray[1] = 0.304005;
//    weightArray[2] = 0.093913;
//}
//
//void setBlurLevel_3()
//{
//    blurIter = 4;
//
//    offsetArray[0] = 0.0;
//    offsetArray[1] = 1.458430;
//    offsetArray[2] = 3.403985;
//    offsetArray[3] = 5.351806;
//    offsetArray[4] = 7.302940;
//
//    weightArray[0] = 0.133571;
//    weightArray[1] = 0.233308;
//    weightArray[2] = 0.135928;
//    weightArray[3] = 0.051383;
//    weightArray[4] = 0.012595;
//}

void main()
{
//    offsetArray[0] = 0.0;
//    offsetArray[1] = 1.489585;
//    offsetArray[2] = 3.475713;
//    offsetArray[3] = 5.461879;
//    offsetArray[4] = 7.448104;
//    offsetArray[5] = 9.434408;
//    offsetArray[6] = 11.420812;
//    offsetArray[7] = 13.407332;
//
//    weightArray[0] = 0.067540;
//    weightArray[1] = 0.130499;
//    weightArray[2] = 0.113686;
//    weightArray[3] = 0.088692;
//    weightArray[4] = 0.061965;
//    weightArray[5] = 0.038768;
//    weightArray[6] = 0.021721;
//    weightArray[7] = 0.010898;
    
//    gl_FragColor = vec4(1.0, vColor.r, 0.0, 1.0);
//    return;
    
    float blurLevel = vColor.r * 10.0;
    
//    if (blurLevel < 0.1) {
//        gl_FragColor = texture2D(sDiffMap, vTexCoord) * vColor.a;
//        return;
//    }
//    else if (blurLevel > 0.1 && blurLevel < 1.1) {
//        setBlurLevel_1();
//    }
//    else if (blurLevel > 1.1 && blurLevel < 2.1) {
//        setBlurLevel_2();
//    }
//    else if (blurLevel > 2.1 && blurLevel < 3.1) {
//        setBlurLevel_3();
//    }
//    else {
//        gl_FragColor = texture2D(sDiffMap, vTexCoord) * vColor.a;
//        return;
//    }
    
    vec4 diffInput = doBlur(blurLevel) * vColor.a;
    gl_FragColor = diffInput;
}

