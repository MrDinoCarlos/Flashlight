package es.mrdino.flashlight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.mrdino.flashlight.light.BeamMetadata;
import es.mrdino.flashlight.light.BeamOptics;
import es.mrdino.flashlight.light.SurfacePatch;
import es.mrdino.flashlight.light.SurfaceProjection;
import es.mrdino.flashlight.math.Vec3;
import es.mrdino.flashlight.pack.ShaderComposer;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Exercises the real resource-pack shaders, models and marker PNGs. No shaderpack or mod. */
class CoreShaderGpuTest {
    private record Sprite(int texture, float u, float v, float width, float height) { }
    private static final int W = 640, H = 360;
    private static final Path PACK = Path.of("resource-pack");

    @Test void theNormalResourcePackRendersBeamImpactDimmerAndOpaqueOcclusionInBothEntityPaths() throws Exception {
        assertTrue(glfwInit());
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = glfwCreateWindow(W, H, "Resource-pack core shader test", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            for (String path : List.of("entity", "rendertype_item_entity_translucent_cull")) {
                try (Renderer renderer = new Renderer(path)) {
                    for (int warmup=0;warmup<8;warmup++) renderer.frame(1,false,true,false);
                    long[] times=new long[15];
                    int query=glGenQueries();
                    renderer.measureQuery=query;
                    for (int sample=0;sample<times.length;sample++) {
                        renderer.drawNanos=0;
                        renderer.frame(1,false,true,false);
                        times[sample]=renderer.drawNanos;
                    }
                    renderer.measureQuery=0;
                    glDeleteQueries(query); java.util.Arrays.sort(times);
                    String version=Files.readString(Path.of("build.gradle.kts")).split("version = \"")[1].split("\"")[0];
                    Files.writeString(Path.of("build","gpu-"+version+"-"+path+".txt"),
                            glGetString(GL_RENDERER)+"; 640x360 air beam; median summed GPU draw ms="+times[times.length/2]/1_000_000.0+" (CPU submission excluded)\n");
                    ByteBuffer full = renderer.frame(1, true, true, false);
                    ByteBuffer dim = renderer.frame(.15, true, true, false);
                    ByteBuffer off = renderer.frame(0, true, true, false);
                    int lit = red(full, W/2, H/2), low = red(dim, W/2, H/2), dark = red(off, W/2, H/2);
                    assertTrue(lit > dark + 30, path + " must render the impact: " + lit + "/" + dark);
                    assertTrue(dark < low && low < lit, path + " must render partial flicker intensity");
                    assertEquals(red(off, 8, 8), red(full, 8, 8), "Only the directed cone is lit");
                    for(int pixel=0;pixel<full.limit();pixel+=4) {
                        int r=full.get(pixel)&255,b=full.get(pixel+2)&255;
                        assertTrue(r>=15 && b<=r,"Marker data must never paint dark or blue triangles into the scene");
                    }
                    ByteBuffer air = renderer.frame(1, false, true, false);
                    save(air, Path.of("build", path + "-resource-pack-beam.png"));
                    assertTrue(red(air,W/2,H/2) > red(air,8,8)+2, path + " must render the analytic air beam: " + red(air,W/2,H/2) + "/" + red(air,8,8));
                    ByteBuffer blocked = renderer.frame(1, true, true, true);
                    assertTrue(red(blocked,W/2,H/2) < lit-25, "Opaque geometry must hide the footprint behind it");
                    int close=peak(renderer.frameAtDistance(1,true,false,false,2));
                    int far=peak(renderer.frameAtDistance(1,true,false,false,16));
                    int limit=peak(renderer.frameAtDistance(1,true,false,false,20));
                    assertTrue(close>far && far>limit, "The actual shader footprint fades with throw distance");
                    assertEquals(15,limit,1,"No residual footprint at maximum range");
                    assertEquals(GL_NO_ERROR, glGetError());
                    save(full, Path.of("build", path + "-resource-pack-impact.png"));
                    save(air, Path.of("build", path + "-resource-pack-beam.png"));
                    ByteBuffer surfaces=renderer.surfaceFrame(1,false),unlit=renderer.surfaceFrame(0,false);
                    assertTrue(red(surfaces,338,160)>red(unlit,338,160)+35,"Foreground receives the real patch shader");
                    assertTrue(red(surfaces,370,180)>red(unlit,370,180)+5,"Background is lit outside the foreground silhouette");
                    ByteBuffer floor=renderer.surfaceFrame(1,true),floorOff=renderer.surfaceFrame(0,true);
                    assertTrue(red(floor,320,138)>red(floorOff,320,138)+10,"A nearly parallel beam reaches the floor");
                    for(int pixel=0;pixel<surfaces.limit();pixel+=4)
                        assertTrue((surfaces.get(pixel)&255)>=15 && (surfaces.get(pixel+2)&255)<=(surfaces.get(pixel)&255),"No visible marker triangles");
                    save(surfaces,Path.of("build",path+"-multiple-surfaces.png"));
                    save(floor,Path.of("build",path+"-grazing-floor.png"));
                    Vec3 wallNormal=new Vec3(0,0,1);
                    ByteBuffer whole=renderer.surfaceFrame(1,List.of(new SurfacePatch(wallNormal,-8,-4,-4,4,4)));
                    ByteBuffer divided=renderer.surfaceFrame(1,List.of(new SurfacePatch(wallNormal,-8,-4,-4,0,4),
                            new SurfacePatch(wallNormal,-8,0,-4,4,4)));
                    int maxDifference=0;
                    for(int pixel=0;pixel<whole.limit();pixel+=4)
                        maxDifference=Math.max(maxDifference,Math.abs((whole.get(pixel)&255)-(divided.get(pixel)&255)));
                    assertTrue(maxDifference<=8,path+" wall partitioning must not visibly pulse the shader: "+maxDifference);
                    Vector3f savedLens=new Vector3f(renderer.lens),savedAxis=new Vector3f(renderer.axis);
                    renderer.lens.set(0,-.03f,3); renderer.axis.set(0,0,-1);
                    renderer.orientation.rotationTo(0,0,1,0,0,-1);
                    ByteBuffer inside=renderer.frameAtDistance(1,false,true,false,12);
                    ByteBuffer insideOff=renderer.frameAtDistance(0,false,true,false,12);
                    assertTrue(red(inside,W/2,H/2)>red(insideOff,W/2,H/2)+2,"The single double-sided carrier also works from inside the cone");
                    renderer.lens.set(savedLens); renderer.axis.set(savedAxis);
                    renderer.orientation.rotationTo(0,0,1,savedAxis.x,savedAxis.y,savedAxis.z);
                    ByteBuffer vanilla=renderer.vanillaGlassFrame(0x80808080);
                    assertEquals(72,red(vanilla,W/2,H/2),3,"Ordinary translucent textures must not be identified as flashlight markers");
                    ByteBuffer blueGlass=renderer.vanillaGlassFrame(0x81000080);
                    assertEquals(7,red(blueGlass,W/2,H/2),3,"Flat colours that match marker bytes keep vanilla rendering");
                    assertEquals(72,blueGlass.get((H/2*W+W/2)*4+2)&255,3);
                    // Real models go through FaceBakery's 4/atlasSize UV shrink,
                    // and OptiFine can use filtering rather than nearest texels.
                    renderer.filteredAtlas();
                    ByteBuffer nativeBeam=renderer.frame(1,true,true,false);
                    assertTrue(red(nativeBeam,W/2,H/2)>dark+30,"Baked and filtered UVs still identify the beam");
                    for(int pixel=0;pixel<nativeBeam.limit();pixel+=4) {
                        int r=nativeBeam.get(pixel)&255,b=nativeBeam.get(pixel+2)&255;
                        assertTrue(r>=15 && b<=r,"Baked UVs must never expose the volume's coordinate textures");
                    }
                }
            }
            for(String host:List.of("trp","trp_strobe","strobe")) {
                try(var renderer=new Renderer("rendertype_item_entity_translucent_cull",host)) {
                    ByteBuffer lit=renderer.frame(1,true,true,false),off=renderer.frame(0,true,true,false);
                    assertTrue(red(lit,W/2,H/2)>red(off,W/2,H/2)+30,host+" keeps the flashlight beam and impact");
                    ByteBuffer surface=renderer.surfaceFrame(1,false),surfaceOff=renderer.surfaceFrame(0,false);
                    assertTrue(red(surface,338,160)>red(surfaceOff,338,160)+30,host+" keeps multi-surface flashlight projection");
                    ByteBuffer vanilla=renderer.vanillaGlassFrame(0x80808080);
                    assertEquals(72,red(vanilla,W/2,H/2),3,host+" retains the host's normal item rendering");
                    save(lit,Path.of("build",host+"-flashlight-compatibility.png"));
                    assertEquals(GL_NO_ERROR,glGetError());
                }
            }
        } finally { GL.setCapabilities(null); glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static final class Renderer implements AutoCloseable {
        final int program, vao = glGenVertexArrays(), vbo = glGenBuffers(), fbo = glGenFramebuffers();
        final List<Integer> textures = new ArrayList<>();
        final Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), (float)W/H, .05f, 128);
        final Sprite grey, white, outer, inner, impact;
        Sprite activeSprite;
        final int atlas;
        final Vector3f lens, axis;
        final Quaternionf orientation;
        final double lensRadius;
        final float[] impactUv;
        final java.util.Map<String,Sprite> surfaceSprites=new java.util.HashMap<>();
        int measureQuery;
        long drawNanos;

        Renderer(String name) throws Exception {
            this(name,null);
        }
        Renderer(String name,String host) throws Exception {
            program = program(name,host);
            var pose=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/flashlight_pose.json"))).getAsJsonObject();
            var hand=pose.getAsJsonObject("hands").getAsJsonObject("right");
            lens=vector(hand,"origin"); axis=vector(hand,"direction");
            lensRadius=pose.get("lens_radius").getAsDouble();
            orientation=new Quaternionf().rotationTo(0,0,1,axis.x,axis.y,axis.z);
            int target = texture(GL_RGBA8, W,H,GL_RGBA), depth = texture(GL_DEPTH_COMPONENT24,W,H,GL_DEPTH_COMPONENT);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
            grey = solid(15,15,15); white = solid(255,255,255);
            // Exercise stitched, off-centre sprites rather than loading each PNG as a whole texture.
            atlas = texture(GL_RGBA8, 1024, 1024, GL_RGBA);
            outer = atlasSprite("volume_0_both_20", "outer", 32, 48);
            inner = atlasSprite("volume_0_both_20", "inner", 300, 216);
            impact = atlasSprite("impact", 592, 448);
            var mask=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/models/beam/impact.json"))).getAsJsonObject()
                    .getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("south").getAsJsonArray("uv");
            float u0=mask.get(0).getAsFloat()/16,v0=mask.get(1).getAsFloat()/16,u1=mask.get(2).getAsFloat()/16,v1=mask.get(3).getAsFloat()/16;
            impactUv=new float[]{u0,v1,u1,v1,u1,v0,u0,v0};
        }

        ByteBuffer frame(double power, boolean footprint, boolean volume, boolean occluded) throws Exception {
            return frameAtDistance(power,footprint,volume,occluded,(-8-lens.z)/axis.z);
        }

        ByteBuffer surfaceFrame(double power,boolean floor) throws Exception {
            return surfaceFrame(power,floor ? List.of(new SurfacePatch(new Vec3(0,1,0),-1.3,-4,2,4,14))
                    : List.of(new SurfacePatch(new Vec3(0,0,1),-8,-4,-4,4,4),new SurfacePatch(new Vec3(0,0,1),-4,-.45,-.9,.55,.6)));
        }

        ByteBuffer surfaceFrame(double power,List<SurfacePatch> faces) throws Exception {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glViewport(0,0,W,H);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glClearColor(15/255f,15/255f,15/255f,1); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
            setup(grey,Color.WHITE,new Display.Brightness(15,15));
            for(SurfacePatch face:faces) surfaceQuad(face,0,new float[]{0,1,1,1,1,0,0,0});
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            if(power>0) for(SurfacePatch face:faces) {
                var data=SurfaceProjection.of(face,new Vec3(lens.x,lens.y,lens.z),new Vec3(axis.x,axis.y,axis.z),lensRadius,28,20,power);
                if(data==null) continue;
                Sprite sprite=surfaceSprites.get(data.model());
                if(sprite==null) {
                    int index=surfaceSprites.size();
                    sprite=atlasSprite(data.model(),512+index%24*16,index/24*16); surfaceSprites.put(data.model(),sprite);
                }
                setup(sprite,data.color(),data.brightness());
                var uv=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/models/beam/"+data.model()+".json")))
                        .getAsJsonObject().getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("south").getAsJsonArray("uv");
                float u0=uv.get(0).getAsFloat()/16,v0=uv.get(1).getAsFloat()/16,u1=uv.get(2).getAsFloat()/16,v1=uv.get(3).getAsFloat()/16;
                surfaceQuad(face,.006,new float[]{u0,v1,u1,v1,u1,v0,u0,v0});
            }
            ByteBuffer result=BufferUtils.createByteBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,result);
            return result;
        }

        void surfaceQuad(SurfacePatch face,double offset,float[] uv) {
            Vec3[] points={face.point(face.minU(),face.minV()),face.point(face.maxU(),face.minV()),
                    face.point(face.maxU(),face.maxV()),face.point(face.minU(),face.maxV())};
            float[] positions=new float[12];
            for(int i=0;i<4;i++) {
                Vec3 point=points[i].add(face.normal().scale(offset));
                positions[i*3]=(float)point.x(); positions[i*3+1]=(float)point.y(); positions[i*3+2]=(float)point.z();
            }
            quad(positions,uv);
        }

        ByteBuffer vanillaGlassFrame(int rgba) {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glClearColor(15/255f,15/255f,15/255f,1);
            glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
            BufferedImage glass=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<16;y++) for(int x=0;x<16;x++) glass.setRGB(x,y,rgba);
            setup(new Sprite(image(glass),0,0,1,1),Color.WHITE,new Display.Brightness(15,15));
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            quad(new float[]{-1,-1,-2,1,-1,-2,1,1,-2,-1,1,-2},new float[]{0,1,1,1,1,0,0,0});
            ByteBuffer result=BufferUtils.createByteBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,result);
            return result;
        }

        ByteBuffer frameAtDistance(double power, boolean footprint, boolean volume, boolean occluded, double length) throws Exception {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glViewport(0,0,W,H);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glClearColor(.012f,.012f,.012f,1); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
            setup(grey, Color.WHITE, new Display.Brightness(15,15));
            Vector3f end=new Vector3f(axis).mul((float)length).add(lens);
            quad(new float[]{-30,-30,end.z, 30,-30,end.z, 30,30,end.z, -30,30,end.z}, new float[]{0,0,1,0,1,1,0,1});
            if (occluded) quad(new float[]{-3,-3,-1, 3,-3,-1, 3,3,-1, -3,3,-1}, new float[]{0,0,1,0,1,1,0,1});
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            double diameter = BeamMetadata.carrierDiameter(length, lensRadius*2 + length * Math.tan(Math.toRadians(14))*2);
            if (power > 0 && footprint) {
                setup(impact, Color.fromRGB((int)Math.round(power*BeamOptics.attenuation(length,20)*255),255,255),new Display.Brightness(15,15));
                float d = (float)diameter/2;
                quad(new float[]{end.x-d,end.y-d,end.z+.012f, end.x+d,end.y-d,end.z+.012f,
                        end.x+d,end.y+d,end.z+.012f, end.x-d,end.y+d,end.z+.012f},impactUv);
            }
            if (power > 0 && volume) {
                Vector3f normal=new Quaternionf(orientation).conjugate().transform(new Vector3f(0,0,1));
                normal.mul((float)diameter,(float)diameter,(float)length);
                BeamMetadata metadata = BeamMetadata.encode(length,diameter,power,normal);
                glEnable(GL_CULL_FACE); glCullFace(GL_BACK);
                carrier("volume_0_both_20",outer,false,length,diameter,metadata);
                glDisable(GL_CULL_FACE);
            }
            ByteBuffer result=BufferUtils.createByteBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,result);
            return result;
        }

        void carrier(String name,Sprite texture,boolean inward,double length,double diameter,BeamMetadata data) throws Exception {
            setup(texture,data.color(),data.brightness());
            JsonObject model=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/models/beam/"+name+".json"))).getAsJsonObject();
            for(var node:model.getAsJsonArray("elements")) {
            JsonObject element=node.getAsJsonObject();
            JsonObject faces=element.getAsJsonObject("faces");
            Vector3f from=vector(element,"from").sub(8,8,8).div(16),to=vector(element,"to").sub(8,8,8).div(16);
            String[] names={"down","up","north","south","west","east"};
            float[] corners={0,0, 0,1, 1,1, 1,0};
            for(int face=0;face<6;face++) {
                if(!faces.has(names[face])) continue;
                setup(faces.getAsJsonObject(names[face]).get("texture").getAsString().equals("#inner") ? inner : texture,data.color(),data.brightness());
                var uv=faces.getAsJsonObject(names[face]).getAsJsonArray("uv");
                float u0=uv.get(0).getAsFloat()/16,v0=uv.get(1).getAsFloat()/16,u1=uv.get(2).getAsFloat()/16,v1=uv.get(3).getAsFloat()/16;
                float middleU=(u0+u1)*.5f,middleV=(v0+v1)*.5f,shrink=4/1024f;
                u0+=(middleU-u0)*shrink; u1+=(middleU-u1)*shrink;
                v0+=(middleV-v0)*shrink; v1+=(middleV-v1)*shrink;
                float[] positions=new float[12],coords=new float[8];
                for(int k=0;k<4;k++) {
                    float u=corners[k*2],v=corners[k*2+1];
                    Vector3f local=switch(face) {
                        case 0 -> new Vector3f(from.x+(to.x-from.x)*u,from.y,to.z-(to.z-from.z)*v);
                        case 1 -> new Vector3f(from.x+(to.x-from.x)*u,to.y,from.z+(to.z-from.z)*v);
                        case 2 -> new Vector3f(to.x-(to.x-from.x)*u,to.y-(to.y-from.y)*v,from.z);
                        case 3 -> new Vector3f(from.x+(to.x-from.x)*u,to.y-(to.y-from.y)*v,to.z);
                        case 4 -> new Vector3f(from.x,to.y-(to.y-from.y)*v,from.z+(to.z-from.z)*u);
                        default -> new Vector3f(to.x,to.y-(to.y-from.y)*v,to.z-(to.z-from.z)*u);
                    };
                    local.mul((float)diameter*(inward?-1:1),(float)diameter,(float)length);
                    orientation.transform(local); local.add(lens);
                    positions[k*3]=local.x; positions[k*3+1]=local.y; positions[k*3+2]=local.z;
                    coords[k*2]=u0+(u1-u0)*u; coords[k*2+1]=v0+(v1-v0)*v;
                }
                quad(positions,coords);
            }
            }
        }

        void setup(Sprite texture,Color tint,Display.Brightness light) {
            activeSprite = texture;
            glUseProgram(program); bind(0,texture.texture); integer(program,"Sampler0",0);
            bind(1,white.texture); integer(program,"Sampler1",1); bind(2,white.texture); integer(program,"Sampler2",2);
            matrix(program,"ModelViewMat",new Matrix4f()); matrix(program,"ProjMat",projection); matrix(program,"TextureMat",new Matrix4f());
            glUniformMatrix3fv(glGetUniformLocation(program,"IViewRotMat"),false,new org.joml.Matrix3f().get(new float[9]));
            glUniform4f(glGetUniformLocation(program,"ColorModulator"),1,1,1,1);
            glUniform1f(glGetUniformLocation(program,"FogStart"),100); glUniform1f(glGetUniformLocation(program,"FogEnd"),128);
            integer(program,"FogShape",0); glUniform3f(glGetUniformLocation(program,"Light0_Direction"),0,1,0);
            glUniform3f(glGetUniformLocation(program,"Light1_Direction"),0,1,0);
            constant("Color",tint.getRed()/255f,tint.getGreen()/255f,tint.getBlue()/255f,1);
            constant("Normal",0,1,0,0);
            int lightAttribute=glGetAttribLocation(program,"UV2");
            if(lightAttribute>=0) { glDisableVertexAttribArray(lightAttribute); glVertexAttribI2i(lightAttribute,light.getBlockLight()*16,light.getSkyLight()*16); }
        }

        void filteredAtlas() {
            glBindTexture(GL_TEXTURE_2D,atlas);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,4); glGenerateMipmap(GL_TEXTURE_2D);
        }

        void constant(String name,float x,float y,float z,float w) {
            int attribute=glGetAttribLocation(program,name);
            if(attribute>=0) { glDisableVertexAttribArray(attribute); glVertexAttrib4f(attribute,x,y,z,w); }
        }

        void quad(float[] positions,float[] uv) {
            int[] indices={0,1,2,0,2,3}; FloatBuffer vertices=BufferUtils.createFloatBuffer(30);
            for(int k:indices) vertices.put(positions,k*3,3)
                    .put(activeSprite.u + uv[k*2] * activeSprite.width)
                    .put(activeSprite.v + uv[k*2+1] * activeSprite.height);
            vertices.flip(); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo); glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            int position=glGetAttribLocation(program,"Position"),texture=glGetAttribLocation(program,"UV0");
            glEnableVertexAttribArray(position); glVertexAttribPointer(position,3,GL_FLOAT,false,20,0);
            glEnableVertexAttribArray(texture); glVertexAttribPointer(texture,2,GL_FLOAT,false,20,12);
            if(measureQuery!=0) glBeginQuery(GL_TIME_ELAPSED,measureQuery);
            glDrawArrays(GL_TRIANGLES,0,6);
            if(measureQuery!=0) { glEndQuery(GL_TIME_ELAPSED); drawNanos+=glGetQueryObjectui64(measureQuery,GL_QUERY_RESULT); }
            glDisableVertexAttribArray(position); glDisableVertexAttribArray(texture);
        }

        int texture(int format,int width,int height,int channels) {
            int texture=glGenTextures(); textures.add(texture); glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,format,width,height,0,channels,GL_FLOAT,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            return texture;
        }
        Sprite solid(int r,int g,int b) { BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB); for(int y=0;y<16;y++) for(int x=0;x<16;x++) image.setRGB(x,y,0xff000000|r<<16|g<<8|b); return new Sprite(image(image),0,0,1,1); }
        Sprite atlasSprite(String modelName, int x, int y) throws Exception {
            return atlasSprite(modelName,null,x,y);
        }
        Sprite atlasSprite(String modelName,String slot,int x,int y) throws Exception {
            var model=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/models/beam/"+modelName+".json"))).getAsJsonObject();
            String id=(slot==null ? model.getAsJsonObject("textures").entrySet().iterator().next().getValue()
                    : model.getAsJsonObject("textures").get(slot)).getAsString();
            String[] parts=id.split(":",2);
            assertTrue(parts[1].startsWith("item/") || parts[1].startsWith("block/"), "Sprite missing from Minecraft's item atlas: " + id);
            BufferedImage image=ImageIO.read(PACK.resolve("assets/"+parts[0]+"/textures/"+parts[1]+".png").toFile());
            glBindTexture(GL_TEXTURE_2D,atlas);
            upload(image,x,y);
            return new Sprite(atlas,x/1024f,y/1024f,image.getWidth()/1024f,image.getHeight()/1024f);
        }
        int image(BufferedImage image) {
            int texture=texture(GL_RGBA8,image.getWidth(),image.getHeight(),GL_RGBA);
            upload(image,0,0); return texture;
        }
        void upload(BufferedImage image,int xOffset,int yOffset) {
            ByteBuffer bytes=BufferUtils.createByteBuffer(image.getWidth()*image.getHeight()*4);
            for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                int rgba=image.getRGB(x,y);
                // Exercise OptiFine's transparent sprite cleanup as well.
                if((rgba>>>24)<=16) rgba=0;
                bytes.put((byte)(rgba>>16)).put((byte)(rgba>>8)).put((byte)rgba).put((byte)(rgba>>>24));
            }
            bytes.flip(); glTexSubImage2D(GL_TEXTURE_2D,0,xOffset,yOffset,image.getWidth(),image.getHeight(),GL_RGBA,GL_UNSIGNED_BYTE,bytes);
        }
        @Override public void close() { glDeleteProgram(program); glDeleteBuffers(vbo); glDeleteVertexArrays(vao); glDeleteFramebuffers(fbo); textures.forEach(org.lwjgl.opengl.GL33C::glDeleteTextures); }
    }

    private static void bind(int unit,int texture) { glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,texture); }
    private static void integer(int p,String n,int v) { glUniform1i(glGetUniformLocation(p,n),v); }
    private static void matrix(int p,String n,Matrix4f value) { glUniformMatrix4fv(glGetUniformLocation(p,n),false,value.get(new float[16])); }
    private static int red(ByteBuffer pixels,int x,int y) { return pixels.get((y*W+x)*4)&255; }
    private static int peak(ByteBuffer pixels) { int peak=0; for(int i=0;i<pixels.limit();i+=4) peak=Math.max(peak,pixels.get(i)&255); return peak; }
    private static Vector3f vector(JsonObject json,String key) { var array=json.getAsJsonArray(key); return new Vector3f(array.get(0).getAsFloat(),array.get(1).getAsFloat(),array.get(2).getAsFloat()); }
    private static void save(ByteBuffer pixels,Path path) throws Exception {
        BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++) for(int x=0;x<W;x++) {int i=(y*W+x)*4; image.setRGB(x,H-1-y,(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|(pixels.get(i+2)&255));}
        ImageIO.write(image,"PNG",path.toFile());
    }
    private static int program(String name,String host) throws Exception {
        int vertex=shader(GL_VERTEX_SHADER,name+".vsh",host),fragment=shader(GL_FRAGMENT_SHADER,name+".fsh",host),program=glCreateProgram();
        glAttachShader(program,vertex); glAttachShader(program,fragment); glBindFragDataLocation(program,0,"fragColor"); glLinkProgram(program);
        assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),name+": "+glGetProgramInfoLog(program));
        glDeleteShader(vertex); glDeleteShader(fragment); return program;
    }
    private static int shader(int type,String name,String host) throws Exception {
        String source=host==null ? source(PACK.resolve("assets/minecraft/shaders/core/"+name))
                : expand(ShaderComposer.compose(Files.readString(Path.of("src/test/resources/compat",host.startsWith("trp") ? "trp" : host,"item."+(type==GL_VERTEX_SHADER ? "vsh" : "fsh")))
                        .replace("TRP_STROBE_PIPELINE_MODE = 0","TRP_STROBE_PIPELINE_MODE = "+(host.equals("trp_strobe") ? "1" : "0")),type==GL_VERTEX_SHADER));
        if(name.startsWith("entity.")) source=source.replace("#version 150","#version 150\n#define ALPHA_CUTOUT 0.1");
        int shader=glCreateShader(type); glShaderSource(shader,source); glCompileShader(shader);
        assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),name+": "+glGetShaderInfoLog(shader)); return shader;
    }
    static String source(Path path) throws Exception {
        return expand(Files.readString(path));
    }
    private static String expand(String content) throws Exception {
        StringBuilder out=new StringBuilder(); var include=Pattern.compile("#moj_import <(?:(\\w+):)?([^>]+)>");
        for(String line:content.lines().toList()) {
            var matcher=include.matcher(line);
            if(matcher.matches()) {
                String namespace=matcher.group(1)==null ? "minecraft" : matcher.group(1);
                Path target=namespace.equals("minecraft") ? matcher.group(2).equals("utils.glsl")
                        ? Path.of("src/test/resources/compat/strobe/utils.glsl") : Path.of("src/test/resources/gpu/minecraft",matcher.group(2))
                        : PACK.resolve("assets/"+namespace+"/shaders/include/"+matcher.group(2));
                // Minecraft's preprocessor merges the imported #version declarations.
                out.append(source(target).replaceAll("(?m)^#version[^\\r\\n]*", ""));
            } else out.append(line).append('\n');
        }
        return out.toString();
    }
}
