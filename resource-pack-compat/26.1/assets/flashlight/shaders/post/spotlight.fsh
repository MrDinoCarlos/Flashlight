#version 330
#moj_import <flashlight:spotlight_data.glsl>
#moj_import <flashlight:optics.glsl>
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D DataSampler;
uniform sampler2D OutlineSampler;
in vec2 texCoord;
flat in uint flActiveLights;
flat in int flFirstLight;
flat in mat4 flProjection;
flat in mat4 flInverseProjection;
flat in vec4 flOriginPower;
flat in vec4 flAxisRange;
flat in vec3 flOptics;
flat in uint flActiveShadows;
flat in vec4 flShadowCenters[8];
flat in vec4 flShadowAxes[8];
out vec4 fragColor;
float flBodyVisibility(vec3 origin,vec3 point) {
    uint remaining=flActiveShadows;
    float visibility=1.0;
    for(int slot=0;slot<FL_MAX_SHADOWS && remaining!=0u;slot++) {
        uint bit=1u<<uint(slot);
        if((remaining & bit)==0u) continue;
        remaining&=~bit;
        vec3 center=flShadowCenters[slot].xyz,up=normalize(flShadowAxes[slot].xyz);
        float radius=max(.025,flShadowCenters[slot].w),height=max(.05,flShadowAxes[slot].w);
        vec3 a=origin-center,b=point-origin;
        a=a/radius+up*dot(a,up)*(1.0/height-1.0/radius);
        b=b/radius+up*dot(b,up)*(1.0/height-1.0/radius);
        float lengthSquared=dot(b,b);
        if(lengthSquared<.000001) continue;
        float nearest=-dot(a,b)/lengthSquared;
        // A receiver on/in the body remains illuminated. Only the geometry
        // beyond its far side receives its cast shadow.
        float discriminant=dot(a,b)*dot(a,b)-lengthSquared*(dot(a,a)-1.0);
        if(discriminant<=0.0) continue;
        float halfSpan=sqrt(discriminant)/lengthSquared;
        if(nearest-halfSpan<=.001 || nearest+halfSpan>=.985) continue;
        float radialSquared=dot(a+b*nearest,a+b*nearest);
        visibility=min(visibility,smoothstep(.65,1.0,radialSquared));
        if(visibility<=0.0) break;
    }
    return visibility;
}
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
    vec4 outline=texture(OutlineSampler,texCoord);
    fragColor=outline;
    if(flActiveLights==0u) return;
    float strength=0.0,haze=0.0,glare=0.0;
    vec3 point=flViewPoint(texCoord,depth,flInverseProjection);
    float viewDistance=length(point);
    vec3 viewRay=point/viewDistance;
    vec3 normal=vec3(0.0);
    bool hasNormal=false;
    uint remaining=flActiveLights;
    for(int slot=flFirstLight;slot<FL_MAX_LIGHTS && remaining!=0u;slot++) {
        uint bit=1u<<uint(slot);
        if((remaining & bit)==0u) continue;
        remaining&=~bit;
        vec4 originPower,axisRange;
        vec3 optics;
        if(slot==flFirstLight) { originPower=flOriginPower; axisRange=flAxisRange; optics=flOptics; }
        else flReadLight(DataSampler,slot,originPower,axisRange,optics);
        vec3 origin=originPower.xyz,axis=axisRange.xyz;
        float power=originPower.w,range=axisRange.w,slope=optics.x,visibleRange=optics.y,visibility=optics.z;
        if(power<=0.0) continue;
        vec4 glareConfig=flCell(DataSampler,slot,26);
        float cameraDistance=length(origin),glareRange=min(range,glareConfig.g*255.0);
        if(glareConfig.r>0.0 && glareConfig.b<.5 && cameraDistance>.15 && cameraDistance<glareRange
            && cameraDistance<=visibleRange+.08 && origin.z<0.0) {
            float toward=dot(-origin,axis);
            float radial=length(-origin-axis*toward)/(FLASHLIGHT_LENS_RADIUS+slope*max(0.0,toward));
            if(toward>0.0 && radial<1.0 && !flShadow(origin,vec3(0.0),flProjection,flInverseProjection)) {
                float facing=pow(clamp(-origin.z/cameraDistance,0.0,1.0),4.0);
                float eyePower=power*glareConfig.r*pow(1.0-cameraDistance/glareRange,2.0)
                    *(1.0-smoothstep(.15,1.0,radial))*facing*flBodyVisibility(origin,vec3(0.0));
                vec4 lens=flProjection*vec4(origin,1.0);
                vec2 lensUV=lens.xy/lens.w*.5+.5;
                vec2 offset=(texCoord-lensUV)*vec2(flProjection[1][1]/flProjection[0][0],1.0);
                glare+=eyePower*(.3+.7*exp(-dot(offset,offset)*32.0));
            }
        }
        if(visibility>0.0) haze+=flAir(viewRay,viewDistance,origin,axis,slope,range,visibleRange,power)*visibility;
        if(depth>=.999999) continue;
        vec3 offset=point-origin;
        float axial=dot(offset,axis),distance=length(offset);
        if(axial<=0.0 || distance>=range || distance>visibleRange+.08) continue;
        float radial=length(offset-axis*axial)/(FLASHLIGHT_LENS_RADIUS+slope*axial);
        if(radial>=1.0) continue;
        float mask=1.0-smoothstep(.62,1.0,radial);
        if(!hasNormal) { normal=flNormal(point,flInverseProjection); hasNormal=true; }
        float incidence=sqrt(clamp(abs(dot(normal,-normalize(offset))),.04,1.0));
        if(flShadow(origin,point,flProjection,flInverseProjection)) continue;
        strength+=power*mask*flAttenuation(distance,range)*incidence*(.45+.55*exp(-2.5*radial*radial))*flBodyVisibility(origin,point);
    }
    if(strength<=0.0 && haze<=0.0 && glare<=0.0) return;
    // Exposure preserves the scene's RGB ratios and texture detail. It never
    // replaces a skin/model with a white colour or emits light at an entity.
    vec3 lit=scene.rgb;
    if(strength>0.0) {
        lit*=1.0+12.0*strength;
        float peak=max(lit.r,max(lit.g,lit.b));
        if(peak>.85) lit*= (.85+.15*(1.0-exp(-(peak-.85)/.15)))/peak;
        lit=max(scene.rgb,lit);
    }
    lit=mix(lit,vec3(1.0,.96,.83),min(haze,.12));
    lit=mix(lit,vec3(1.0,.97,.88),clamp(glare,0.0,.85));
    // Encode the same exposure as a straight-alpha overlay for Minecraft's
    // normal outline blit. No post pass may clear or write minecraft:main:
    // PostPass does not enable depth testing, so gl_FragDepth cannot restore
    // a cleared main depth buffer for particles/weather drawn afterwards.
    vec3 difference=lit-scene.rgb;
    vec3 capacity=mix(scene.rgb,vec3(1.0)-scene.rgb,step(vec3(0.0),difference));
    vec3 ratio=abs(difference)/max(capacity,vec3(.000001));
    float opacity=ceil(clamp(max(ratio.r,max(ratio.g,ratio.b)),0.0,1.0)*255.0)/255.0;
    if(opacity<=0.0) return;
    vec3 lightColor=clamp(scene.rgb+difference/opacity,0.0,1.0);
    // Keep ordinary glowing entities on top, including partially blurred edges.
    // Fabulous composes water/glass after the opaque scene sampled above.
    // An opaque light overlay would replace those later layers entirely.
    // Keep at least 60% of their colour/animation, even on bright receiving blocks.
    float lightWeight=min(opacity,.40+min(glare,.45))*(1.0-outline.a);
    float alpha=outline.a+lightWeight;
    fragColor=vec4((outline.rgb*outline.a+lightColor*lightWeight)/max(alpha,.000001),alpha);
}
