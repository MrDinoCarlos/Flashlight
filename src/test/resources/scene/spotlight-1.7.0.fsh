#version 150
#moj_import <flashlight:spotlight_data.glsl>
#moj_import <flashlight:optics.glsl>
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D DataSampler;
in vec2 texCoord;
flat in uint flActiveLights;
out vec4 fragColor;
float flAttenuation(float distance,float range) {
    float edge=clamp((distance/range-.65)/.35,0.0,1.0);
    return (1.0-edge*edge*(3.0-2.0*edge))/(1.0+distance*distance/36.0);
}
// Integrate the air on the camera ray, stopping at the real scene depth.
// Drawing it here keeps translucent carrier depth out of the surface renderer.
float flAir(vec3 ray,float sceneDistance,vec3 origin,vec3 axis,float slope,float range,float visibleRange,float power) {
    vec3 offset=-origin;
    float axial=dot(offset,axis),along=dot(ray,axis);
    float near=0.0,far=sceneDistance;
    float reach=min(range,visibleRange);
    if(abs(along)<.000001) {
        if(axial<0.0 || axial>reach) return 0.0;
    } else {
        float a=-axial/along,b=(reach-axial)/along;
        near=max(near,min(a,b)); far=min(far,max(a,b));
    }
    float centre=dot(offset,ray);
    float sphere=centre*centre-dot(offset,offset)+reach*reach;
    if(sphere<0.0) return 0.0;
    near=max(near,-centre-sqrt(sphere)); far=min(far,-centre+sqrt(sphere));
    float expansion=1.0+slope*slope;
    float a=1.0-expansion*along*along;
    float b=2.0*(centre-expansion*along*axial-FLASHLIGHT_LENS_RADIUS*slope*along);
    float c=dot(offset,offset)-expansion*axial*axial
        -2.0*FLASHLIGHT_LENS_RADIUS*slope*axial-FLASHLIGHT_LENS_RADIUS*FLASHLIGHT_LENS_RADIUS;
    if(abs(a)<.000001) {
        if(abs(b)<.000001) { if(c>0.0) return 0.0; }
        else if(b>0.0) far=min(far,-c/b);
        else near=max(near,-c/b);
    } else {
        float discriminant=b*b-4.0*a*c;
        if(discriminant<0.0) { if(a>0.0) return 0.0; }
        else {
            float one=(-b-sqrt(discriminant))/(2.0*a),two=(-b+sqrt(discriminant))/(2.0*a);
            float low=min(one,two),high=max(one,two);
            if(a>0.0) { near=max(near,low); far=min(far,high); }
            else if(near<low) far=min(far,low);
            else near=max(near,high);
        }
    }
    if(far<=near) return 0.0;
    float stepLength=(far-near)/10.0,density=0.0;
    for(int sampleIndex=0;sampleIndex<10;sampleIndex++) {
        vec3 point=ray*(near+(float(sampleIndex)+.5)*stepLength)-origin;
        float z=dot(point,axis);
        float radial=length(point-axis*z)/(FLASHLIGHT_LENS_RADIUS+slope*z);
        float profile=1.0-smoothstep(.68,1.0,radial);
        density+=profile*(.25+.75*exp(-2.5*radial*radial))*flAttenuation(length(point),range)
            *smoothstep(.25,1.25,z)*stepLength;
    }
    return .055*power*(1.0-exp(-density*.22));
}
vec3 flViewPoint(vec2 uv,float depth,mat4 inverseProjection) {
    vec4 p=inverseProjection*vec4(uv*2.0-1.0,depth*2.0-1.0,1.0);
    return p.xyz/p.w;
}
bool flShadow(vec3 origin,vec3 point,mat4 projection,mat4 inverseProjection) {
    // Screen-space occlusion along the optical ray; outside-screen geometry
    // is left to the existing server collision/volume limits.
    for(int sampleIndex=1;sampleIndex<=10;sampleIndex++) {
        float t=float(sampleIndex)/11.0;
        vec3 probe=mix(origin,point,t);
        vec4 clip=projection*vec4(probe,1.0);
        if(clip.w<=0.0) continue;
        vec3 ndc=clip.xyz/clip.w;
        vec2 uv=ndc.xy*.5+.5;
        if(any(lessThan(uv,vec2(0))) || any(greaterThan(uv,vec2(1)))) continue;
        float sceneDepth=texture(DepthSampler,uv).r;
        // Compare in view space to keep the tolerance independent of distance.
        vec3 surface=flViewPoint(uv,sceneDepth,inverseProjection);
        if(sceneDepth<.999999 && surface.z>probe.z+.10 && length(surface-origin)<length(point-origin)-.12) return true;
    }
    return false;
}
vec3 flNormal(vec3 point,mat4 inverseProjection) {
    vec2 pixel=1.0/vec2(textureSize(DepthSampler,0));
    vec2 left=texCoord-vec2(pixel.x,0),right=texCoord+vec2(pixel.x,0);
    vec2 down=texCoord-vec2(0,pixel.y),up=texCoord+vec2(0,pixel.y);
    vec3 a=point-flViewPoint(left,texture(DepthSampler,left).r,inverseProjection);
    vec3 b=flViewPoint(right,texture(DepthSampler,right).r,inverseProjection)-point;
    vec3 c=point-flViewPoint(down,texture(DepthSampler,down).r,inverseProjection);
    vec3 d=flViewPoint(up,texture(DepthSampler,up).r,inverseProjection)-point;
    vec3 n=cross(abs(a.z)<abs(b.z) ? a : b,abs(c.z)<abs(d.z) ? c : d);
    return dot(n,n)>.000000000001 ? normalize(n) : normalize(-point);
}
void main() {
    vec4 scene=texture(InSampler,texCoord);
    float depth=texture(DepthSampler,texCoord).r;
    fragColor=scene; gl_FragDepth=depth;
    if(flActiveLights==0u) return;
    float strength=0.0,haze=0.0;
    for(int slot=0;slot<FL_MAX_LIGHTS;slot++) {
        if((flActiveLights & (1u<<uint(slot)))==0u) continue;
        vec3 origin=vec3(flDecodeFloat(flCell(DataSampler,slot,1)),flDecodeFloat(flCell(DataSampler,slot,2)),flDecodeFloat(flCell(DataSampler,slot,3)));
        vec3 axis=normalize(vec3(flDecodeFloat(flCell(DataSampler,slot,4)),flDecodeFloat(flCell(DataSampler,slot,5)),flDecodeFloat(flCell(DataSampler,slot,6))));
        ivec3 bytes=ivec3(round(flCell(DataSampler,slot,7).rgb*255.0));
        int word=bytes.r|(bytes.g<<8)|(bytes.b<<16);
        float power=float((word>>5)&255)/255.0;
        if(power<=0.0) continue;
        float range=4.0+float((word>>13)&31);
        float slope=tan(radians(4.0+float((word>>18)&63))*.5);
        mat4 projection;
        for(int column=0;column<4;column++) for(int row=0;row<4;row++) projection[column][row]=flDecodeFloat(flCell(DataSampler,slot,8+column*4+row));
        mat4 inverseProjection=inverse(projection);
        vec3 point=flViewPoint(texCoord,depth,inverseProjection);
        float visibleRange=flDecodeFloat(flCell(DataSampler,slot,24));
        haze+=flAir(normalize(point),length(point),origin,axis,slope,range,visibleRange,power);
        if(depth>=.999999) continue;
        vec3 offset=point-origin;
        float axial=dot(offset,axis),distance=length(offset);
        if(axial<=0.0 || distance>=range || distance>visibleRange+.08) continue;
        float radial=length(offset-axis*axial)/(FLASHLIGHT_LENS_RADIUS+slope*axial);
        if(radial>=1.0) continue;
        float mask=1.0-smoothstep(.62,1.0,radial);
        vec3 normal=flNormal(point,inverseProjection);
        float incidence=sqrt(clamp(abs(dot(normal,-normalize(offset))),.04,1.0));
        if(flShadow(origin,point,projection,inverseProjection)) continue;
        strength+=power*mask*flAttenuation(distance,range)*incidence*(.45+.55*exp(-2.5*radial*radial));
    }
    if(strength<=0.0 && haze<=0.0) return;
    // Exposure preserves the scene's RGB ratios and texture detail. It never
    // replaces a skin/model with a white colour or emits light at an entity.
    vec3 lit=scene.rgb;
    if(strength>0.0) {
        lit*=1.0+12.0*strength;
        float peak=max(lit.r,max(lit.g,lit.b));
        if(peak>.85) lit*= (.85+.15*(1.0-exp(-(peak-.85)/.15)))/peak;
        lit=max(scene.rgb,lit);
    }
    fragColor=vec4(mix(lit,vec3(1.0,.96,.83),min(haze,.12)),scene.a);
}
