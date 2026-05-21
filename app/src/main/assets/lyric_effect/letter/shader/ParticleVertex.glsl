//#version 410 core
#define TARGET_IPHONE 1

precision highp float;

attribute vec4 iPos;
attribute vec4 iColor;
attribute vec2 iTexCoord;

varying vec2 vTexCoord;
varying vec4 vColor;

uniform mat4 cViewProj;
uniform mat4 cModel;

#define iModelMatrix cModel

vec3 GetWorldPos(mat4 modelMatrix)
{
    return (iPos * modelMatrix).xyz;
}

vec4 GetClipPos(vec3 worldPos)
{
    vec4 ret = vec4(worldPos, 1.0) * cViewProj;
    return ret;
}

void main()
{
    mat4 modelMatrix = iModelMatrix;
    vec3 worldPos = GetWorldPos(modelMatrix);
    //gl_Position = GetClipPos(worldPos);
    gl_Position = vec4(iPos.xyz, 1.0);
    //gl_Position.z = 0.0;
    gl_PointSize = iColor.g * 150.0;
    //gl_PointSize = 100.0;
    
    vTexCoord = iTexCoord;
    vColor = iColor;
    vColor.a = iColor.a;
}

