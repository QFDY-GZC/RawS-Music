//#version 410 core

precision highp float;
varying vec2 vTexCoord;
varying vec4 vColor;

uniform sampler2D sDiffMap;
uniform vec4 cParam1;   //xy:频谱位置；z:频谱段数；w:频谱最大高度
uniform vec4 cParam2;   //x:(圆半径); y:圆柱，点，直线，曲线的宽度(0.0~1.0); z:?; w:边缘柔化程度(0.0~1.0)
uniform vec4 cParam3;   //rgba:频谱颜色
  
#define ZERO_NEAR 0.0001
#define M_PI 3.14159265
const vec4 BackgroundColor = vec4(0.0, 0.0, 0.0, 0.0);
float CircleModeWidthScale = 1.0;

float floatLimit(float f)
{
    if (abs(f) < ZERO_NEAR) {
        if (f < 0.0) {
            return -ZERO_NEAR;
        }
        else {
            return ZERO_NEAR;
        }
    }
    return f;
}

vec2 coordTransY()
{
    vec2 uv = vTexCoord;
    uv.y = 1.0 - vTexCoord.y;
    return uv;
}

//float glow(float dis)
//{
//    float dist = pow(dis, 0.8); //cParam1.y相当于曝光度
//    float col = 1.0 - exp(-dist) * 1.0;
//    return col;
//}

vec4 drawCylinder(float cyWidth, float cyHeight, vec2 localPostion)
{
    float smoothness = cParam2.w * cyWidth * 0.5;
    
    if (localPostion.y >= cParam1.y && localPostion.y < cyHeight + cParam1.y) {
        float cyAlpha = smoothstep(0.0, smoothness, localPostion.x) - smoothstep(cyWidth - smoothness, cyWidth, localPostion.x);
        //float dist = abs(localPostion.x - 0.5);
        //float cyAlpha = glow(dist);
        return mix(BackgroundColor, cParam3, cyAlpha);
    }
    return BackgroundColor;
}

vec4 drawSpectrum()
{
    vec2 uv = coordTransY();
    float xStep = 1.0 / cParam1.z;
    float width = cParam2.y * CircleModeWidthScale;
    float maxHeight = cParam1.w;
    
    float scaleX = uv.x * cParam1.z;
    float spTextureCoordU = floor(scaleX) * xStep;
    float localX = scaleX - floor(scaleX);
    
    float spHeight = texture2D(sDiffMap, vec2(spTextureCoordU, 0.5)).r * maxHeight;
    float spHeight2 = texture2D(sDiffMap, vec2(spTextureCoordU, 0.5)).g * maxHeight;
    
    vec4 color1 = drawCylinder(width, spHeight, vec2(localX, uv.y));
    vec4 color2 = drawCylinder(width, spHeight2 * 0.8, vec2(localX, uv.y));
    if (color1.r < 0.001) {
        return color2 * 0.3;
    }
    //return color1 + color2 * 0.3;
    return color1;
}

void main()
{
    gl_FragColor = drawSpectrum();
}



