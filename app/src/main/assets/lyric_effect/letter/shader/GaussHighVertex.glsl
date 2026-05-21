precision highp float;

attribute vec4 iPos;
attribute vec4 iColor;
attribute vec2 iTexCoord;

uniform float cTexelWidthOffset;
uniform float cTexelHeightOffset;

uniform vec4 cTexOffset0;
uniform vec4 cTexOffset1;

varying vec2 blurCoordinates[15];

 void main()
 {
     gl_Position = iPos;

     vec2 singleStepOffset = vec2(cTexelWidthOffset, cTexelHeightOffset);
     blurCoordinates[0] = iTexCoord.xy;
     blurCoordinates[1] = iTexCoord.xy + singleStepOffset * cTexOffset0.x;
     blurCoordinates[2] = iTexCoord.xy - singleStepOffset * cTexOffset0.x;
     blurCoordinates[3] = iTexCoord.xy + singleStepOffset * cTexOffset0.y;
     blurCoordinates[4] = iTexCoord.xy - singleStepOffset * cTexOffset0.y;
     blurCoordinates[5] = iTexCoord.xy + singleStepOffset * cTexOffset0.z;
     blurCoordinates[6] = iTexCoord.xy - singleStepOffset * cTexOffset0.z;
     blurCoordinates[7] = iTexCoord.xy + singleStepOffset * cTexOffset0.w;
     blurCoordinates[8] = iTexCoord.xy - singleStepOffset * cTexOffset0.w;
     blurCoordinates[9] = iTexCoord.xy + singleStepOffset * cTexOffset1.x;
     blurCoordinates[10] = iTexCoord.xy - singleStepOffset * cTexOffset1.x;
     blurCoordinates[11] = iTexCoord.xy + singleStepOffset * cTexOffset1.y;
     blurCoordinates[12] = iTexCoord.xy - singleStepOffset * cTexOffset1.y;
     blurCoordinates[13] = iTexCoord.xy + singleStepOffset * cTexOffset1.z;
     blurCoordinates[14] = iTexCoord.xy - singleStepOffset * cTexOffset1.z;
 }
