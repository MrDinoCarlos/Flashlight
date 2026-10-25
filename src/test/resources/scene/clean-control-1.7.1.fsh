#version 150
#moj_import <flashlight:spotlight_data.glsl>
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    ivec2 pixel=ivec2(gl_FragCoord.xy);
    int slot=pixel.y/FL_CONTROL_HEIGHT;
    if (pixel.x<FL_CONTROL_WIDTH && slot<FL_MAX_LIGHTS && flHeader(flCell(InSampler,slot,0))) {
        fragColor=vec4(0.0); return;
    }
    fragColor=texture(InSampler,texCoord);
}
