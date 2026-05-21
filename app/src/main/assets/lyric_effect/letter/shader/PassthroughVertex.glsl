//#version 410 core

#define TARGET_IPHONE 1


#if TARGET_IPHONE
precision highp float;

attribute vec4 iPos;
attribute vec4 iColor;
attribute vec2 iTexCoord;

varying vec2 vTexCoord;
varying vec4 vColor;

#else
in vec4 iPos;
in vec4 iColor;
in vec2 iTexCoord;

out vec2 vTexCoord;
out vec4 vColor;
#endif


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
    gl_Position = vec4(iPos.xyz, 1.0);
    
    vTexCoord = iTexCoord;
    vColor = iColor;
}

