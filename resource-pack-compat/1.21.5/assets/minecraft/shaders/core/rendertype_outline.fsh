#version 150
#moj_import <flashlight:spotlight_data.glsl>
#moj_import <flashlight:optics.glsl>
uniform sampler2D Sampler0;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 flOriginalPosition;
in vec2 flControlUV;
flat in int flIsControl;
flat in int flControlKind;
flat in vec4 flControlParams;
out vec4 fragColor;
void main() {
    if (flIsControl != 0) {
        vec2 dx=dFdx(flControlUV),dy=dFdy(flControlUV);
        float determinant=dx.x*dy.y-dx.y*dy.x;
        if (abs(determinant)<0.0000000001) discard;
        vec3 right=(dFdx(flOriginalPosition)*dy.y-dFdy(flOriginalPosition)*dx.y)/determinant;
        vec3 up=(-dFdx(flOriginalPosition)*dy.x+dFdy(flOriginalPosition)*dx.x)/determinant;
        vec3 origin=flOriginalPosition-right*(flControlUV.x-.5)-up*(flControlUV.y-.5);
        vec3 axis=normalize(cross(right,up));
        bool shadow=flControlKind==14;
        if(shadow) axis=normalize(up);
        // This private copy is visible only to its owner. In first person its
        // ray follows the rendered camera instead of delayed server packets.
        bool local=flControlKind==12 || flControlKind==13;
        bool cameraTracked=local && length(origin)<2.2;
        if(cameraTracked) {
            origin=flControlKind==12 ? FLASHLIGHT_RIGHT_ORIGIN : FLASHLIGHT_LEFT_ORIGIN;
            axis=flControlKind==12 ? FLASHLIGHT_RIGHT_DIRECTION : FLASHLIGHT_LEFT_DIRECTION;
        }
        int cell=int(floor(gl_FragCoord.x));
        if (cell==0) fragColor=vec4(shadow ? vec3(83,72,68) : vec3(70,76,84),1.0)/255.0;
        else if (cell<=3) fragColor=flEncodeFloat(origin[cell-1]);
        else if (cell<=6) fragColor=flEncodeFloat(axis[cell-4]);
        else if (cell==7) fragColor=vec4(vertexColor.rgb,1.0);
        else if (cell<24) fragColor=flEncodeFloat(ProjMat[(cell-8)/4][(cell-8)%4]);
        else if (cell==24) {
            float extent=length(up)*(shadow ? .5 : 1.0);
            if(cameraTracked) {
                // The local camera ray already stops at scene depth. A sparse
                // server scan must not pulse its air when breathing crosses an edge.
                ivec2 bytes=ivec2(round(vertexColor.gb*255.0));
                extent=4.0+float(((bytes.x>>5)|(bytes.y<<3))&31);
            }
            fragColor=flEncodeFloat(extent);
        }
        else if (cell==25) fragColor=flEncodeFloat(shadow ? length(right)*.5 : max(0.0,length(right)-1.0));
        else fragColor=vec4(flControlParams.rg,local ? 1.0 : 0.0,1.0);
        return;
    }
    // Keep vanilla glowing entities and every other plugin's outline colours.
    vec4 color=texture(Sampler0,texCoord0);
    if (color.a==0.0) discard;
    fragColor=vec4(ColorModulator.rgb*vertexColor.rgb,ColorModulator.a);
}
