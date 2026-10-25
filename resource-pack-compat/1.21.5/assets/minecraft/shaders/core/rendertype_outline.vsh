#version 150
#moj_import <flashlight:spotlight_data.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform sampler2D Sampler0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 flOriginalPosition;
out vec2 flControlUV;
flat out int flIsControl;
flat out int flControlKind;
flat out vec4 flControlParams;
void main() {
    vertexColor = Color;
    texCoord0 = UV0;
    vec4 view = ModelViewMat * vec4(Position,1.0);
    flOriginalPosition = view.xyz;
    flControlUV = vec2(0.0);
    vec4 marker=textureLod(Sampler0,UV0,0.0);
    flIsControl = flControlTexel(marker) ? 1 : 0;
    flControlKind=int(round(marker.r*255.0));
    flControlParams=vec4(0.0);
    gl_Position = ProjMat * view;
    if (flIsControl != 0) {
        int word = int(round(Color.r*255.0)) | (int(round(Color.g*255.0))<<8) | (int(round(Color.b*255.0))<<16);
        int slot = word & 31;
        int corner = gl_VertexID % 4;
        flControlUV = corner==0 ? vec2(0,1) : corner==1 ? vec2(0,0) : corner==2 ? vec2(1,0) : vec2(1,1);
        vec2 toward=vec2(.5-flControlUV.x,flControlUV.y-.5)*2.0;
        flControlParams=textureLod(Sampler0,UV0+toward*6.0/vec2(textureSize(Sampler0,0)),0.0);
        vec2 pixel = vec2(flControlUV.x*float(FL_CONTROL_WIDTH), (float(slot)+flControlUV.y)*float(FL_CONTROL_HEIGHT)
            +(flControlKind==14 ? float(FL_SHADOW_Y) : 0.0));
        gl_Position = vec4(pixel / ScreenSize * 2.0 - 1.0,0.0,1.0);
    }
}
