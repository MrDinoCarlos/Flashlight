package es.mrdino.flashlight;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** The exact pack programs, RGBA8 transport, native pass order and depth writes. */
class SpotlightGpuTest {
    private static final int W=640,H=360;
    private static final Path PACK=Path.of("resource-pack");
    record Target(int framebuffer,int color,int depth,int width,int height) { }

    @Test void clipsTexturedMobsAndPlayersToTheConeWithoutAnEmitterAtTheirPosition() throws Exception {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        long window=glfwCreateWindow(W,H,"FlashLight scene projection",0,0); assertNotEquals(0,window);
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            try(var gpu=new Pipeline()) {
                for(String skin:List.of("creeper","steve")) {
                    gpu.scene(skin); ByteBuffer original=gpu.pixels(gpu.scene);
                    float[] originalDepth=gpu.depth(gpu.scene);
                    gpu.privateCarrier();
                    assertBuffer(original,gpu.pixels(gpu.scene),"The private controller cannot paint over a texture");
                    gpu.controller(1,new Vector3f(0,0,-1));
                    gpu.verifyData();
                    ByteBuffer reference=gpu.referenceImage(); gpu.project();
                    ByteBuffer full=gpu.pixels(gpu.scene);
                    assertAppearance(reference,full,"Keep the accepted 1.7.0 beam, exposure and texture colours");
                    gpu.scene(skin); gpu.controller(1,new Vector3f(0,0,-1)); gpu.setupGraph(); gpu.renderGraph();
                    assertAppearance(full,gpu.pixels(gpu.scene),"The complete resource-pack graph keeps the same beam and hides private data");
                    save(original,Path.of("build",skin+"-spotlight-off.png")); save(full,Path.of("build",skin+"-spotlight-on.png"));
                    int changed=0,colored=0,unchangedBody=0;
                    for(int y=118;y<242;y++) for(int x=258;x<382;x++) {
                        int offset=(y*W+x)*4;
                        int before=max(original,offset),after=max(full,offset);
                        if(after>before+8) {
                            changed++;
                            int r=full.get(offset)&255,g=full.get(offset+1)&255,b=full.get(offset+2)&255;
                            if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>12) colored++;
                        } else unchangedBody++;
                    }
                    assertTrue(changed>250,skin+" must be visible where the cone reaches it: "+changed);
                    assertTrue(colored>150,skin+" keeps real skin/texture colours: "+colored);
                    assertTrue(unchangedBody>100,skin+" body portions outside the cone remain naturally dark");
                    for(int x:new int[]{260,380}) for(int y:new int[]{120,240})
                        assertPixel(original,full,x,y,"The skin itself stays unchanged outside the cone");
                    for(int y=35;y<H-35;y+=17) for(int x=35;x<W-35;x+=17) {
                        // Whole-scene check, including the wall right beside the entity.
                        float worldX=(float)((x+.5-W*.5)/(W*.5)*4/gpu.projection.m00());
                        float worldY=(float)((y+.5-H*.5)/(H*.5)*4/gpu.projection.m11());
                        if(Math.hypot(worldX,worldY)>2.2) assertPixel(original,full,x,y,"No radial light around "+skin);
                    }
                    float[] afterDepth=gpu.depth(gpu.scene);
                    for(int pixel=0;pixel<originalDepth.length;pixel++) assertEquals(originalDepth[pixel],afterDepth[pixel],1e-7,"Native depth is retained for TRP/Strobe/translucency");

                    gpu.scene(skin); gpu.controller(.15,new Vector3f(0,0,-1)); gpu.project();
                    ByteBuffer dim=gpu.pixels(gpu.scene);
                    assertTrue(sum(dim)>sum(original) && sum(dim)<sum(full),"Partial flicker changes the cone, not the world's lighting");
                    gpu.scene(skin); gpu.clear(gpu.data); gpu.project();
                    assertBuffer(original,gpu.pixels(gpu.scene),"No controller leaves every scene pixel unchanged");
                    for(int slot:new int[]{17,31}) {
                        gpu.scene(skin); gpu.controller(1,new Vector3f(0,0,-1),slot); gpu.project();
                        assertBuffer(full,gpu.pixels(gpu.scene),"A tracked light works when slot zero is absent, including the highest slot");
                        gpu.scene(skin); gpu.controller(1,new Vector3f(0,0,-1),slot); gpu.renderGraph();
                        assertAppearance(full,gpu.pixels(gpu.scene),"The compact metadata target retains every tracked slot");
                    }
                    gpu.scene(skin); gpu.controller(0,new Vector3f(0,0,-1)); gpu.project();
                    assertBuffer(original,gpu.pixels(gpu.scene),"Power zero has no residual emitter");
                    gpu.scene(skin); gpu.controller(1,new Vector3f(.7f,0,-1).normalize()); gpu.project();
                    ByteBuffer turned=gpu.pixels(gpu.scene);
                    assertPixel(original,turned,320,180,"Turning away stops illuminating the body");
                }
                gpu.vanillaOutline();
                gpu.lateParticles();
                gpu.outlineComposition();
                gpu.air();
                gpu.airVisibility();
                gpu.multiplayerShadows();
                gpu.cameraTracking();
                gpu.faceGlare();
                try(var full=new Pipeline(1920,1080)) { full.profileNativeGraph(); }
                gpu.water();
                assertEquals(GL_NO_ERROR,glGetError());
            }
        } finally { GL.setCapabilities(null); glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static final class Pipeline implements AutoCloseable {
        final List<Integer> textures=new ArrayList<>(),programs=new ArrayList<>(),framebuffers=new ArrayList<>();
        final int W,H;
        final int vao=glGenVertexArrays(),vbo=glGenBuffers(),indices=glGenBuffers();
        final Matrix4f projection;
        final int entity,outline,screen,particle,reference,spotlight,clean,nativeSobel,mergedSobel,marker,white,overlay,ambient,wall;
        final Target scene,data,lit,cleaned,outlines,referenceTarget,fluid,composite,empty;
        final int terrain,transparency,copper,waterStill,waterFlow;
        final int localRight,localLeft,shadowMarker,noGlare;
        int controllerMarker;
        JsonObject graph;
        final Map<String,Target> graphTargets=new LinkedHashMap<>();
        final Map<String,Integer> graphPrograms=new LinkedHashMap<>();
        Pipeline() throws Exception { this(SpotlightGpuTest.W,SpotlightGpuTest.H); }
        Pipeline(int width,int height) throws Exception {
            W=width; H=height;
            projection=new Matrix4f().perspective((float)Math.toRadians(70),(float)W/H,.05f,128);
            scene=target(); data=target(); lit=target(); cleaned=target(); outlines=target(); referenceTarget=target();
            fluid=target(); composite=target(); empty=target();
            entity=program("assets/minecraft/shaders/core/entity.vsh","assets/minecraft/shaders/core/entity.fsh",true);
            outline=program("assets/minecraft/shaders/core/rendertype_outline.vsh","assets/minecraft/shaders/core/rendertype_outline.fsh",false);
            spotlight=post("spotlight");
            clean=program("assets/flashlight/shaders/post/spotlight.vsh","src/test/resources/scene/clean-control-1.7.1.fsh",false);
            nativeSobel=program("src/test/resources/gpu/minecraft/post_sobel.vsh","src/test/resources/gpu/minecraft/post_entity_sobel.fsh",false);
            mergedSobel=program("assets/flashlight/shaders/post/outline.vsh","assets/flashlight/shaders/post/outline.fsh",false);
            screen=program("src/test/resources/gpu/minecraft/blit_screen.vsh","src/test/resources/gpu/minecraft/blit_screen.fsh",false);
            particle=program("src/test/resources/gpu/minecraft/particle.vsh","src/test/resources/gpu/minecraft/particle.fsh",false);
            reference=program("assets/flashlight/shaders/post/spotlight.vsh","src/test/resources/scene/spotlight-1.7.0.fsh",false);
            terrain=program("src/test/resources/gpu/minecraft/terrain.vsh","src/test/resources/gpu/minecraft/terrain.fsh",false);
            transparency=program("src/test/resources/gpu/minecraft/post_screenquad.vsh","src/test/resources/gpu/minecraft/post_transparency.fsh",false);
            marker=image(ImageIO.read(PACK.resolve("assets/flashlight/textures/item/beam/entity_control.png").toFile()));
            localRight=image(ImageIO.read(PACK.resolve("assets/flashlight/textures/item/beam/entity_control_right.png").toFile()));
            localLeft=image(ImageIO.read(PACK.resolve("assets/flashlight/textures/item/beam/entity_control_left.png").toFile()));
            shadowMarker=image(ImageIO.read(PACK.resolve("assets/flashlight/textures/item/beam/shadow_control.png").toFile()));
            var disabledGlare=ImageIO.read(PACK.resolve("assets/flashlight/textures/item/beam/entity_control.png").toFile());
            for(int y=4;y<12;y++) for(int x=4;x<12;x++) disabledGlare.setRGB(x,y,0xff000c00);
            noGlare=image(disabledGlare); controllerMarker=marker;
            white=solid(255,255,255,255); overlay=solid(0,0,0,255); ambient=solid(9,9,9,255); wall=solid(125,118,108,255);
            copper=solid(204,64,15,255);
            waterStill=image(ImageIO.read(Path.of("src/test/resources/scene/water_still.png").toFile()));
            waterFlow=image(ImageIO.read(Path.of("src/test/resources/scene/water_flow.png").toFile()));
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,0,2,3},GL_STATIC_DRAW);
            clear(outlines); clear(empty);
        }
        int post(String name) throws Exception { return program("assets/flashlight/shaders/post/spotlight.vsh","assets/flashlight/shaders/post/"+name+".fsh",false); }
        int program(String vertex,String fragment,boolean cutout) throws Exception {
            int program=glCreateProgram(); programs.add(program);
            for(String path:List.of(vertex,fragment)) {
                String source=CoreShaderGpuTest.source(path.startsWith("assets/") ? PACK.resolve(path) : Path.of(path));
                if(cutout) source=source.replace("#version 150","#version 150\n#define ALPHA_CUTOUT 0.1");
                int shader=glCreateShader(path.endsWith(".vsh") ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
                glShaderSource(shader,source); glCompileShader(shader);
                assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),path+": "+glGetShaderInfoLog(shader));
                glAttachShader(program,shader); glDeleteShader(shader);
            }
            glBindFragDataLocation(program,0,"fragColor"); glLinkProgram(program);
            assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),vertex+": "+glGetProgramInfoLog(program)); return program;
        }
        Target target() {
            return target(W,H);
        }
        Target target(int width,int height) {
            int fbo=glGenFramebuffers(); framebuffers.add(fbo);
            int color=texture(GL_RGBA8,GL_RGBA,width,height),depth=texture(GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT,width,height);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER)); return new Target(fbo,color,depth,width,height);
        }
        int texture(int format,int channels) {
            return texture(format,channels,W,H);
        }
        int texture(int format,int channels,int width,int height) {
            int texture=glGenTextures(); textures.add(texture); glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,format,width,height,0,channels,GL_FLOAT,(ByteBuffer)null); filtering(); return texture;
        }
        int solid(int r,int g,int b,int a) { var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB); for(int y=0;y<2;y++) for(int x=0;x<2;x++) image.setRGB(x,y,a<<24|r<<16|g<<8|b); return image(image); }
        int image(BufferedImage image) {
            int texture=glGenTextures(); textures.add(texture); glBindTexture(GL_TEXTURE_2D,texture);
            ByteBuffer bytes=BufferUtils.createByteBuffer(image.getWidth()*image.getHeight()*4);
            for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) { int rgba=image.getRGB(x,y); bytes.put((byte)(rgba>>16)).put((byte)(rgba>>8)).put((byte)rgba).put((byte)(rgba>>>24)); }
            bytes.flip(); glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,image.getWidth(),image.getHeight(),0,GL_RGBA,GL_UNSIGNED_BYTE,bytes); filtering(); return texture;
        }
        void filtering() { glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE); }
        void clear(Target target) {
            glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer); glViewport(0,0,target.width,target.height);
            glDepthMask(true); glClearDepth(1); glClearColor(0,0,0,0); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        }
        void scene(String skin) throws Exception {
            clear(outlines);
            clear(scene); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glUseProgram(entity); matrices(entity,projection); bind(entity,"Sampler0",0,wall); bind(entity,"Sampler1",1,overlay); bind(entity,"Sampler2",2,ambient);
            glUniform4f(glGetUniformLocation(entity,"ColorModulator"),1,1,1,1);
            glUniform1f(glGetUniformLocation(entity,"FogStart"),100); glUniform1f(glGetUniformLocation(entity,"FogEnd"),128);
            glUniform1i(glGetUniformLocation(entity,"FogShape"),0);
            glUniform3f(glGetUniformLocation(entity,"Light0_Direction"),0,1,0); glUniform3f(glGetUniformLocation(entity,"Light1_Direction"),0,1,0);
            constant(entity,"Color",1,1,1,1); constant(entity,"Normal",0,1,0,0);
            int uv2=glGetAttribLocation(entity,"UV2"); if(uv2>=0) glVertexAttribI2i(uv2,0,0);
            quad(entity,new float[]{-10,6,-8,-10,-6,-8,10,-6,-8,10,6,-8},new float[]{0,0,0,1,1,1,1,0});
            BufferedImage image=ImageIO.read(Path.of("src/test/resources/scene/"+skin+".png").toFile()); int texture=image(image);
            bind(entity,"Sampler0",0,texture);
            bind(entity,"Sampler2",2,ambient);
            float u0=8f/image.getWidth(),v0=8f/image.getHeight(),u1=16f/image.getWidth(),v1=16f/image.getHeight();
            quad(entity,new float[]{-1,1,-4,-1,-1,-4,1,-1,-4,1,1,-4},new float[]{u0,v0,u0,v1,u1,v1,u1,v0});
        }
        void controller(double power,Vector3f axis) {
            controller(power,axis,0);
        }
        void controller(double power,Vector3f axis,int slot) {
            controller(power,axis,slot,new Vector3f());
        }
        void controller(double power,Vector3f axis,int slot,Vector3f origin) {
            controller(power,axis,slot,origin,1);
        }
        void controller(double power,Vector3f axis,int slot,Vector3f origin,double visibility) {
            clear(data); appendController(power,axis,slot,origin,visibility);
        }
        void appendController(double power,Vector3f axis,int slot,Vector3f origin,double visibility) {
            glBindFramebuffer(GL_FRAMEBUFFER,data.framebuffer); glViewport(0,0,W,H);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glUseProgram(outline); matrices(outline,projection); bind(outline,"Sampler0",0,controllerMarker);
            glUniform2f(glGetUniformLocation(outline,"ScreenSize"),W,H);
            glUniform4f(glGetUniformLocation(outline,"ColorModulator"),1,1,1,1);
            int word=slot | (int)Math.round(power*255)<<5 | 16<<13 | 24<<18;
            constant(outline,"Color",(word&255)/255f,(word>>>8&255)/255f,(word>>>16&255)/255f,1);
            var rotation=new Quaternionf().rotationTo(new Vector3f(0,0,1),axis);
            float[] positions=new float[12]; float[] uv={0,0,0,1,1,1,1,0};
            for(int i=0;i<4;i++) {
                var p=new Vector3f((i<2 ? -.5f : .5f)*(float)(1+visibility),i==0||i==3 ? 10 : -10,0); rotation.transform(p).add(origin);
                positions[i*3]=p.x; positions[i*3+1]=p.y; positions[i*3+2]=p.z;
            }
            quad(outline,positions,uv);
        }
        void appendShadow(Vector3f centre) {
            glBindFramebuffer(GL_FRAMEBUFFER,data.framebuffer); glViewport(0,0,W,H);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glUseProgram(outline); matrices(outline,projection); bind(outline,"Sampler0",0,shadowMarker);
            glUniform2f(glGetUniformLocation(outline,"ScreenSize"),W,H);
            constant(outline,"Color",0,0,0,1);
            quad(outline,new float[]{centre.x-.3f,centre.y+.9f,centre.z,centre.x-.3f,centre.y-.9f,centre.z,
                    centre.x+.3f,centre.y-.9f,centre.z,centre.x+.3f,centre.y+.9f,centre.z},new float[]{0,0,0,1,1,1,1,0});
        }
        void multiplayerShadows() throws Exception {
            scene("creeper"); controller(1,new Vector3f(0,0,-1)); project(); ByteBuffer lit=pixels(scene);
            scene("creeper"); controller(1,new Vector3f(0,0,-1)); appendShadow(new Vector3f(0,0,-2)); project();
            ByteBuffer shadow=pixels(scene);
            assertTrue(max(lit,(H/2*W+W/2)*4)>max(shadow,(H/2*W+W/2)*4)+15,
                    "A shared off-screen body casts a shadow without appearing in this observer's depth image");
            assertPixel(lit,shadow,40,40,"A cast shadow never darkens natural light outside the flashlight");
            save(shadow,Path.of("build/shared-player-shadow.png"));
            scene("creeper"); controller(1,new Vector3f(0,0,-1)); appendShadow(new Vector3f(0,0,-7)); project();
            assertAppearance(lit,pixels(scene),"A caster behind the receiver cannot shadow that receiver");
        }
        void cameraTracking() throws Exception {
            var pose=JsonParser.parseString(Files.readString(PACK.resolve("assets/flashlight/flashlight_pose.json"))).getAsJsonObject().getAsJsonObject("hands");
            for(String hand:List.of("right","left")) {
                var side=pose.getAsJsonObject(hand); var origin=side.getAsJsonArray("origin"); var direction=side.getAsJsonArray("direction");
                Vector3f current=new Vector3f(origin.get(0).getAsFloat(),origin.get(1).getAsFloat(),origin.get(2).getAsFloat());
                Vector3f axis=new Vector3f(direction.get(0).getAsFloat(),direction.get(1).getAsFloat(),direction.get(2).getAsFloat());
                controllerMarker=marker; scene("creeper"); controller(1,axis,0,current); project(); ByteBuffer direct=pixels(scene);
                controllerMarker=hand.equals("right") ? localRight : localLeft;
                scene("creeper"); controller(1,new Vector3f(.35f,.1f,-1).normalize(),0,new Vector3f(.1f,-.4f,-1)); project();
                assertAppearance(direct,pixels(scene),"The owner's "+hand+" beam uses the current camera pose despite delayed server transforms");
                controllerMarker=marker; scene("creeper"); controller(1,new Vector3f(0,0,-1),0,new Vector3f(0,0,-3)); project(); ByteBuffer external=pixels(scene);
                controllerMarker=hand.equals("right") ? localRight : localLeft;
                scene("creeper"); controller(1,new Vector3f(0,0,-1),0,new Vector3f(0,0,-3)); project();
                assertAppearance(external,pixels(scene),"F5 keeps the world-space ray when the camera is away from the held item");
            }
            controllerMarker=marker;
        }
        ByteBuffer glareFrame(float distance,Vector3f axis,boolean enabled) {
            clear(scene); glClearColor(.06f,.04f,.02f,1); glClear(GL_COLOR_BUFFER_BIT);
            controllerMarker=enabled ? marker : noGlare;
            controller(1,axis,0,new Vector3f(0,0,-distance)); project(); controllerMarker=marker; return pixels(scene);
        }
        void faceGlare() throws Exception {
            ByteBuffer close=glareFrame(2,new Vector3f(0,0,1),true),far=glareFrame(10,new Vector3f(0,0,1),true);
            ByteBuffer disabled=glareFrame(2,new Vector3f(0,0,1),false),miss=glareFrame(2,new Vector3f(1,0,0),true);
            int centre=(H/2*W+W/2)*4;
            assertTrue(max(close,centre)>max(disabled,centre)+25,"A nearby flashlight aimed into the face produces a visible glare");
            assertTrue(max(close,centre)>max(far,centre)+20,"Glare weakens with distance");
            assertTrue(max(miss,centre)<max(close,centre)-20,"Pointing away from the viewer removes the face glare");
            scene("creeper"); controller(1,new Vector3f(0,0,1),0,new Vector3f(0,0,-6)); project(); ByteBuffer blocked=pixels(scene);
            scene("creeper"); controllerMarker=noGlare; controller(1,new Vector3f(0,0,1),0,new Vector3f(0,0,-6)); project();
            assertAppearance(blocked,pixels(scene),"A solid body between the lens and the face blocks glare");
            controllerMarker=marker; save(close,Path.of("build/face-glare-close.png"));
        }
        void verifyData() {
            ByteBuffer bytes=pixels(data); int signature=(1*W)*4;
            assertEquals(70,bytes.get(signature)&255); assertEquals(76,bytes.get(signature+1)&255); assertEquals(84,bytes.get(signature+2)&255);
            for(int column=0;column<16;column++) {
                int offset=(W+8+column)*4;
                int bits=(bytes.get(offset)&255)|(bytes.get(offset+1)&255)<<8|(bytes.get(offset+2)&255)<<16|(bytes.get(offset+3)&255)<<24;
                assertEquals(projection.get(column/4,column%4),Float.intBitsToFloat(bits),.000001,"Full projection matrix survives the native RGBA8 carrier");
            }
        }
        void project() {
            post(spotlight,lit,scene.color,scene.depth,data.color);
            blendScreen(lit.color);
        }
        ByteBuffer referenceImage() {
            post(reference,referenceTarget,scene.color,scene.depth,data.color);
            return pixels(referenceTarget);
        }
        void blendScreen(int texture) {
            // LevelRenderer.doEntityOutline -> RenderTarget.blitToScreen:
            // no clear, RGB-only alpha blending, no depth test or depth writes.
            glBindFramebuffer(GL_FRAMEBUFFER,scene.framebuffer); glViewport(0,0,W,H);
            glColorMask(true,true,true,false); glDisable(GL_DEPTH_TEST); glDepthMask(false);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ZERO,GL_ONE);
            glUseProgram(screen); bind(screen,"InSampler",0,texture);
            quad(screen,new float[]{0,1,0,0,0,0,1,0,0,1,1,0},new float[]{0,1,0,0,1,0,1,1});
            glDepthMask(true); glColorMask(true,true,true,true); glDisable(GL_BLEND);
        }
        void particle(float z,float x) {
            glBindFramebuffer(GL_FRAMEBUFFER,scene.framebuffer); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
            glDepthMask(true); glDisable(GL_BLEND); glUseProgram(particle); matrices(particle,projection);
            bind(particle,"Sampler0",0,white); bind(particle,"Sampler2",2,white);
            glUniform4f(glGetUniformLocation(particle,"ColorModulator"),1,1,1,1);
            glUniform1f(glGetUniformLocation(particle,"FogStart"),100); glUniform1f(glGetUniformLocation(particle,"FogEnd"),128);
            glUniform1i(glGetUniformLocation(particle,"FogShape"),0);
            constant(particle,"Color",.05f,.9f,.8f,1);
            int uv2=glGetAttribLocation(particle,"UV2"); if(uv2>=0) glVertexAttribI2i(uv2,0,0);
            quad(particle,new float[]{x-.4f,.4f,z,x-.4f,-.4f,z,x+.4f,-.4f,z,x+.4f,.4f,z},new float[]{0,0,0,1,1,1,1,0});
        }
        void lateParticles() throws Exception {
            for(double power:new double[]{0,1}) {
                scene("creeper"); float[] originalDepth=depth(scene);
                controller(power,new Vector3f(0,0,-1));
                ByteBuffer before=pixels(scene);
                post(spotlight,lit,scene.color,scene.depth,data.color);
                assertBuffer(before,pixels(scene),"The outline pass must never clear or overwrite the main target");
                particle(-10,1.9f);
                assertBuffer(before,pixels(scene),"Particles behind solid geometry remain occluded after post-processing");
                assertArrayEquals(originalDepth,depth(scene),"Post passes leave native depth untouched even with depth testing disabled");
                particle(-2,-.7f); blendScreen(lit.color);
                ByteBuffer withFront=pixels(scene);
                assertTrue((withFront.get((180*W+230)*4+1)&255)>100,"Visible foreground particles still render normally");
                particle(-10,1.9f);
                assertBuffer(withFront,pixels(scene),"The native final blit also preserves solid-block occlusion");
            }
        }
        void privateCarrier() {
            glBindFramebuffer(GL_FRAMEBUFFER,scene.framebuffer); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
            glUseProgram(entity); bind(entity,"Sampler0",0,marker);
            quad(entity,new float[]{-1,1,-2,-1,-1,-2,1,-1,-2,1,1,-2},new float[]{0,0,0,1,1,1,1,0});
        }
        void air() throws Exception {
            clear(outlines); clear(scene); ByteBuffer off=pixels(scene);
            controller(1,new Vector3f(0,0,-1),0,new Vector3f(.28f,-.22f,-.6f)); project();
            ByteBuffer on=pixels(scene); int peak=0;
            for(int pixel=0;pixel<on.limit();pixel+=4) peak=Math.max(peak,max(on,pixel));
            assertTrue(peak>=4,"The air beam remains visible with no wall or entity in front of it");
            assertPixel(off,on,10,10,"No haze outside the flashlight cone");
            assertPixel(off,on,W-10,H-10,"No haze outside the flashlight cone");
            for(float depth:depth(scene)) assertEquals(1,depth,1e-7,"Air cannot change scene depth or cover entities");
            save(on,Path.of("build/post-resource-pack-beam.png"));
            int query=glGenQueries(); long[] samples=new long[15];
            for(int i=-5;i<samples.length;i++) {
                clear(scene); glBeginQuery(GL_TIME_ELAPSED,query); project(); glEndQuery(GL_TIME_ELAPSED);
                long elapsed=glGetQueryObjecti64(query,GL_QUERY_RESULT); if(i>=0) samples[i]=elapsed;
            }
            glDeleteQueries(query); java.util.Arrays.sort(samples);
            String version=Files.readString(Path.of("build.gradle.kts")).split("version = \"")[1].split("\"")[0];
            Files.writeString(Path.of("build/gpu-"+version+"-spotlight.txt"),glGetString(GL_RENDERER)
                    +"; 640x360 single-light cone/air overlay and native RGB blit; median GPU ms="+samples[samples.length/2]/1_000_000.0
                    +" (outline stages and CPU excluded)\n");
        }
        void airVisibility() throws Exception {
            Vector3f origin=new Vector3f(.28f,-.22f,-.6f),axis=new Vector3f(0,0,-1);
            clear(scene); ByteBuffer off=pixels(scene);
            controller(1,axis,0,origin,0); project();
            assertBuffer(off,pixels(scene),"Visibility zero hides only the air beam");
            clear(scene); controller(1,axis,0,origin,1); project(); long normal=sum(pixels(scene));
            clear(scene); controller(1,axis,0,origin,3); project();
            assertTrue(sum(pixels(scene))>normal,"Air beam visibility can be increased independently");
            scene("creeper"); ByteBuffer dark=pixels(scene);
            controller(1,axis,0,new Vector3f(),0); project();
            assertTrue(sum(pixels(scene))>sum(dark),"Visibility zero keeps direct illumination on real textures");
        }
        void post(int program,Target destination,int color,int depth,int data) {
            // PostPass changes the depth function but does not enable depth testing.
            // Native RenderType cleanup leaves it disabled before the outline chain.
            clear(destination); glDisable(GL_DEPTH_TEST); glDepthFunc(GL_ALWAYS); glDepthMask(true); glDisable(GL_BLEND);
            glUseProgram(program); matrices(program,new Matrix4f().setOrtho(0,W,0,H,.1f,1000));
            glUniform2f(glGetUniformLocation(program,"OutSize"),W,H);
            glUniform2f(glGetUniformLocation(program,"InSize"),W,H);
            bind(program,"InSampler",0,color); bind(program,"DepthSampler",1,depth); bind(program,"DataSampler",2,data);
            bind(program,"OutlineSampler",3,outlines.color);
            quad(program,new float[]{0,H,0,0,0,0,W,0,0,W,H,0},new float[]{0,1,0,0,1,0,1,1});
        }
        void vanillaOutline() {
            controller(1,new Vector3f(0,0,-1));
            post(clean,cleaned,data.color,0,0);
            ByteBuffer privateData=pixels(cleaned);
            assertEquals(0,sum(privateData),"Private optical data never becomes an outline or a bright artefact");
            for(int pixel=3;pixel<privateData.limit();pixel+=4) assertEquals(0,privateData.get(pixel));
            clear(data); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glUseProgram(outline); matrices(outline,projection);
            bind(outline,"Sampler0",0,white); glUniform4f(glGetUniformLocation(outline,"ColorModulator"),1,1,1,1);
            constant(outline,"Color",.2f,.8f,.4f,1);
            quad(outline,new float[]{-1,1,-4,-1,-1,-4,1,-1,-4,1,1,-4},new float[]{0,0,0,1,1,1,1,0});
            ByteBuffer original=pixels(data); post(clean,cleaned,data.color,0,0); assertBuffer(original,pixels(cleaned),"Other plugins' real outlines remain unchanged");
            for(boolean ordinaryGlow:new boolean[]{false,true}) {
                controller(1,new Vector3f(0,0,-1));
                if(ordinaryGlow) drawNormalOutline(data,false);
                post(clean,cleaned,data.color,0,0); post(nativeSobel,outlines,cleaned.color,0,0);
                ByteBuffer expected=pixels(outlines);
                post(mergedSobel,referenceTarget,data.color,0,0);
                assertBuffer(expected,pixels(referenceTarget),"Merged masking/outline detection is identical to the two original passes");
            }
        }
        void normalOutline() {
            drawNormalOutline(outlines,true);
        }
        void drawNormalOutline(Target target,boolean erase) {
            if(erase) clear(target); else glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glUseProgram(outline); matrices(outline,projection);
            bind(outline,"Sampler0",0,white); glUniform4f(glGetUniformLocation(outline,"ColorModulator"),1,1,1,.35f);
            constant(outline,"Color",.2f,.8f,.4f,1);
            quad(outline,new float[]{-1,1,-4,-1,-1,-4,1,-1,-4,1,1,-4},new float[]{0,0,0,1,1,1,1,0});
        }
        void outlineComposition() throws Exception {
            scene("creeper"); normalOutline(); blendScreen(outlines.color); ByteBuffer vanilla=pixels(scene);
            scene("creeper"); normalOutline(); controller(0,new Vector3f(0,0,-1)); project();
            assertBuffer(vanilla,pixels(scene),"Normal outlines retain their native alpha blending");
            scene("creeper"); normalOutline(); controller(1,new Vector3f(0,0,-1));
            referenceImage(); blendScreen(referenceTarget.color); blendScreen(outlines.color); ByteBuffer expected=pixels(scene);
            scene("creeper"); normalOutline(); controller(1,new Vector3f(0,0,-1)); project();
            assertAppearance(expected,pixels(scene),"Glow outlines stay above the light, including partially transparent edges");
        }
        void brightScene() {
            clear(outlines); clear(scene); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glUseProgram(terrain); matrices(terrain,projection); bind(terrain,"Sampler0",0,copper); bind(terrain,"Sampler2",2,white);
            glUniform4f(glGetUniformLocation(terrain,"ColorModulator"),1,1,1,1);
            glUniform1f(glGetUniformLocation(terrain,"FogStart"),100); glUniform1f(glGetUniformLocation(terrain,"FogEnd"),128);
            constant(terrain,"Color",1,1,1,1); int uv2=glGetAttribLocation(terrain,"UV2"); glVertexAttribI2i(uv2,0,0);
            quad(terrain,new float[]{-10,6,-6,-10,-6,-6,10,-6,-6,10,6,-6},new float[]{0,0,0,1,1,1,1,0});
        }
        void waterQuad(Target destination,int texture,int frame) {
            glBindFramebuffer(GL_FRAMEBUFFER,destination.framebuffer); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glUseProgram(terrain); matrices(terrain,projection); bind(terrain,"Sampler0",0,texture); bind(terrain,"Sampler2",2,white);
            glUniform4f(glGetUniformLocation(terrain,"ColorModulator"),1,1,1,1);
            glUniform1f(glGetUniformLocation(terrain,"FogStart"),100); glUniform1f(glGetUniformLocation(terrain,"FogEnd"),128);
            constant(terrain,"Color",63/255f,118/255f,228/255f,1);
            glVertexAttribI2i(glGetAttribLocation(terrain,"UV2"),0,0);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            float top=frame/32f,bottom=(frame+1)/32f;
            quad(terrain,new float[]{-3,2,-4,-3,-2,-4,3,-2,-4,3,2,-4},new float[]{0,top,0,bottom,1,bottom,1,top});
            glDisable(GL_BLEND);
        }
        void resolveWater() {
            clear(composite); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glUseProgram(transparency);
            matrices(transparency,new Matrix4f().setOrtho(0,W,0,H,.1f,1000));
            glUniform2f(glGetUniformLocation(transparency,"OutSize"),W,H);
            bind(transparency,"MainSampler",0,scene.color); bind(transparency,"MainDepthSampler",1,scene.depth);
            bind(transparency,"TranslucentSampler",2,fluid.color); bind(transparency,"TranslucentDepthSampler",3,fluid.depth);
            int unit=4;
            for(String input:List.of("ItemEntity","Particles","Clouds","Weather")) {
                bind(transparency,input+"Sampler",unit++,empty.color); bind(transparency,input+"DepthSampler",unit++,empty.depth);
            }
            quad(transparency,new float[]{0,H,0,0,0,0,W,0,0,W,H,0},new float[]{0,1,0,0,1,0,1,1});
            blendScreen(composite.color);
        }
        ByteBuffer waterFrame(boolean fabulous,boolean wet,double power,int texture,int frame) {
            brightScene(); clear(fluid);
            if(wet) {
                if(fabulous) {
                    glBindFramebuffer(GL_READ_FRAMEBUFFER,scene.framebuffer); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fluid.framebuffer);
                    glBlitFramebuffer(0,0,W,H,0,0,W,H,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
                }
                waterQuad(fabulous ? fluid : scene,texture,frame);
            }
            controller(power,new Vector3f(0,0,-1)); post(spotlight,lit,scene.color,scene.depth,data.color);
            if(fabulous) resolveWater();
            blendScreen(lit.color); return pixels(scene);
        }
        long waterDifference(ByteBuffer first,ByteBuffer second) {
            long difference=0;
            for(int y=H/2-20;y<H/2+20;y++) for(int x=W/2-20;x<W/2+20;x++) for(int channel=0;channel<3;channel++) {
                int index=(y*W+x)*4+channel; difference+=Math.abs((first.get(index)&255)-(second.get(index)&255));
            }
            return difference;
        }
        void water() throws Exception {
            for(boolean fabulous:new boolean[]{true,false}) for(int texture:new int[]{waterStill,waterFlow}) {
                ByteBuffer dry=waterFrame(fabulous,false,0,texture,0),wet=waterFrame(fabulous,true,0,texture,0);
                ByteBuffer dryLit=waterFrame(fabulous,false,1,texture,0),wetLit=waterFrame(fabulous,true,1,texture,0);
                save(wetLit,Path.of("build/water-"+(fabulous ? "fabulous" : "fancy")+"-lit.png"));
                long normal=waterDifference(dry,wet),illuminated=waterDifference(dryLit,wetLit);
                assertTrue(normal>10000,"The native water texture must be visible before enabling the flashlight");
                assertTrue(illuminated>=normal*.55,"Water must retain colour/detail over bright blocks in "
                        +(fabulous ? "Fabulous" : "Fancy")+": "+illuminated+" / "+normal);
                ByteBuffer next=waterFrame(fabulous,true,0,texture,12),nextLit=waterFrame(fabulous,true,1,texture,12);
                long animation=waterDifference(wet,next),litAnimation=waterDifference(wetLit,nextLit);
                assertTrue(animation>100,"The animated water fixture must contain motion");
                // Fancy illuminates the water itself, so tone compression also
                // changes its contrast. Fabulous preserves the later layer directly.
                assertTrue(litAnimation>=animation*(fabulous ? .55 : .40),"The flashlight must preserve the flowing water animation in "
                        +(fabulous ? "Fabulous" : "Fancy")+": "+litAnimation+" / "+animation);
            }
        }
        void defaults(int program,JsonObject config) {
            glUseProgram(program);
            for(var element:config.getAsJsonArray("uniforms")) {
                var uniform=element.getAsJsonObject(); int location=glGetUniformLocation(program,uniform.get("name").getAsString());
                float[] values=new float[uniform.getAsJsonArray("values").size()];
                for(int i=0;i<values.length;i++) values[i]=uniform.getAsJsonArray("values").get(i).getAsFloat();
                if(values.length==16) glUniformMatrix4fv(location,false,values);
                else if(values.length==4) glUniform4fv(location,values);
                else if(values.length==3) glUniform3fv(location,values);
                else if(values.length==2) glUniform2fv(location,values);
                else glUniform1fv(location,values);
            }
        }
        void setupGraph() throws Exception {
            if(graph!=null) return;
            graph=JsonParser.parseString(Files.readString(PACK.resolve("assets/minecraft/post_effect/entity_outline.json"))).getAsJsonObject();
            graphTargets.put("minecraft:main",scene); graphTargets.put("minecraft:entity_outline",data);
            for(var entry:graph.getAsJsonObject("targets").entrySet()) {
                var size=entry.getValue().getAsJsonObject();
                graphTargets.put(entry.getKey(),target(size.has("width") ? size.get("width").getAsInt() : W,
                        size.has("height") ? size.get("height").getAsInt() : H));
            }
            for(var element:graph.getAsJsonArray("passes")) {
                String name=element.getAsJsonObject().get("program").getAsString(); if(graphPrograms.containsKey(name)) continue;
                String[] id=name.split(":",2);
                Path path=id[0].equals("minecraft") ? Path.of("src/test/resources/gpu/minecraft",id[1].replace('/','_')+".json")
                        : PACK.resolve("assets/"+id[0]+"/shaders/"+id[1]+".json");
                var config=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String[] sources=new String[2];
                for(int i=0;i<2;i++) {
                    String[] shader=config.get(i==0 ? "vertex" : "fragment").getAsString().split(":",2);
                    sources[i]=shader[0].equals("minecraft") ? "src/test/resources/gpu/minecraft/"+shader[1].replace('/','_')+(i==0 ? ".vsh" : ".fsh")
                            : "assets/"+shader[0]+"/shaders/"+shader[1]+(i==0 ? ".vsh" : ".fsh");
                }
                int program=program(sources[0],sources[1],false); defaults(program,config); graphPrograms.put(name,program);
            }
        }
        void renderGraph() {
            for(var element:graph.getAsJsonArray("passes")) {
                var stage=element.getAsJsonObject(); int program=graphPrograms.get(stage.get("program").getAsString());
                Target output=graphTargets.get(stage.get("output").getAsString());
                clear(output); glDisable(GL_DEPTH_TEST); glDepthFunc(GL_ALWAYS); glDisable(GL_BLEND); glUseProgram(program);
                matrices(program,new Matrix4f().setOrtho(0,W,0,H,.1f,1000));
                glUniform2f(glGetUniformLocation(program,"OutSize"),output.width,output.height);
                int unit=0;
                for(var input:stage.getAsJsonArray("inputs")) {
                    var entry=input.getAsJsonObject(); Target source=graphTargets.get(entry.get("target").getAsString());
                    String sampler=entry.get("sampler_name").getAsString();
                    bind(program,sampler+"Sampler",unit++,entry.has("use_depth_buffer") && entry.get("use_depth_buffer").getAsBoolean() ? source.depth : source.color);
                    glUniform2f(glGetUniformLocation(program,sampler+"Size"),source.width,source.height);
                    if(entry.has("bilinear") && entry.get("bilinear").getAsBoolean()) {
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                    }
                }
                if(stage.has("uniforms")) defaults(program,stage);
                quad(program,new float[]{0,output.height,0,0,0,0,output.width,0,0,output.width,output.height,0},new float[]{0,1,0,0,1,0,1,1});
                unit=0;
                for(var input:stage.getAsJsonArray("inputs")) {
                    var entry=input.getAsJsonObject();
                    if(entry.has("bilinear") && entry.get("bilinear").getAsBoolean()) {
                        glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,graphTargets.get(entry.get("target").getAsString()).color);
                        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                    }
                    unit++;
                }
            }
            blendScreen(data.color);
        }
        void profileNativeGraph() throws Exception {
            setupGraph(); StringBuilder report=new StringBuilder(glGetString(GL_RENDERER)+"; "+W+"x"+H+"; complete shipped outline chain + native RGB blit\n");
            long targetBytes=graphTargets.entrySet().stream().filter(entry->!entry.getKey().startsWith("minecraft:"))
                    .mapToLong(entry->(long)entry.getValue().width*entry.getValue().height*8).sum();
            report.append("passes=").append(graph.getAsJsonArray("passes").size()).append("; internal target allocation bytes=").append(targetBytes).append('\n');
            int query=glGenQueries();
            for(int lights:new int[]{1,4}) {
                long[] samples=new long[15];
                for(int frame=-5;frame<samples.length;frame++) {
                    scene("creeper"); clear(data);
                    for(int slot=0;slot<lights;slot++) appendController(1,new Vector3f(0,0,-1),slot,new Vector3f(slot*.08f,0,0),1);
                    glBeginQuery(GL_TIME_ELAPSED,query); renderGraph(); glEndQuery(GL_TIME_ELAPSED);
                    long elapsed=glGetQueryObjecti64(query,GL_QUERY_RESULT); if(frame>=0) samples[frame]=elapsed;
                }
                java.util.Arrays.sort(samples); report.append("lights=").append(lights).append("; median GPU ms=").append(samples[7]/1_000_000.0)
                        .append("; p90 GPU ms=").append(samples[13]/1_000_000.0).append('\n');
            }
            glDeleteQueries(query);
            String version=Files.readString(Path.of("build.gradle.kts")).split("version = \"")[1].split("\"")[0];
            Files.writeString(Path.of("build/gpu-"+version+"-fullchain-"+W+"x"+H+".txt"),report);
        }
        void matrices(int program,Matrix4f projection) {
            matrix(program,"ModelViewMat",new Matrix4f()); matrix(program,"TextureMat",new Matrix4f()); matrix(program,"ProjMat",projection);
        }
        void constant(int program,String name,float x,float y,float z,float w) {
            glBindVertexArray(vao); int location=glGetAttribLocation(program,name);
            if(location>=0) { glDisableVertexAttribArray(location); glVertexAttrib4f(location,x,y,z,w); }
        }
        void quad(int program,float[] positions,float[] uv) {
            FloatBuffer values=BufferUtils.createFloatBuffer(20);
            for(int i=0;i<4;i++) values.put(positions,i*3,3).put(uv,i*2,2); values.flip();
            glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo); glBufferData(GL_ARRAY_BUFFER,values,GL_STREAM_DRAW);
            int position=glGetAttribLocation(program,"Position"),texture=glGetAttribLocation(program,"UV0");
            glEnableVertexAttribArray(position); glVertexAttribPointer(position,3,GL_FLOAT,false,20,0);
            if(texture>=0) { glEnableVertexAttribArray(texture); glVertexAttribPointer(texture,2,GL_FLOAT,false,20,12); }
            glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L);
            glDisableVertexAttribArray(position); if(texture>=0) glDisableVertexAttribArray(texture);
        }
        ByteBuffer pixels(Target target) { glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer); ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels); return pixels; }
        float[] depth(Target target) { glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer); float[] values=new float[W*H]; glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,values); return values; }
        @Override public void close() { framebuffers.forEach(org.lwjgl.opengl.GL33C::glDeleteFramebuffers); textures.forEach(org.lwjgl.opengl.GL33C::glDeleteTextures); programs.forEach(org.lwjgl.opengl.GL33C::glDeleteProgram); glDeleteBuffers(vbo); glDeleteBuffers(indices); glDeleteVertexArrays(vao); }
    }
    private static void bind(int program,String name,int unit,int texture) { glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,texture); glUniform1i(glGetUniformLocation(program,name),unit); }
    private static void matrix(int program,String name,Matrix4f matrix) { glUniformMatrix4fv(glGetUniformLocation(program,name),false,matrix.get(new float[16])); }
    private static int max(ByteBuffer bytes,int offset) { return Math.max(bytes.get(offset)&255,Math.max(bytes.get(offset+1)&255,bytes.get(offset+2)&255)); }
    private static long sum(ByteBuffer bytes) { long sum=0; for(int i=0;i<bytes.limit();i+=4) sum+=(bytes.get(i)&255)+(bytes.get(i+1)&255)+(bytes.get(i+2)&255); return sum; }
    private static void assertPixel(ByteBuffer a,ByteBuffer b,int x,int y,String reason) { for(int c=0;c<4;c++) assertEquals(a.get((y*W+x)*4+c),b.get((y*W+x)*4+c),reason+" at "+x+","+y); }
    private static void assertBuffer(ByteBuffer a,ByteBuffer b,String reason) { assertEquals(a,b,reason); }
    private static void assertAppearance(ByteBuffer a,ByteBuffer b,String reason) {
        int difference=0; for(int pixel=0;pixel<a.limit();pixel++) difference=Math.max(difference,Math.abs((a.get(pixel)&255)-(b.get(pixel)&255)));
        assertTrue(difference<=2,reason+"; maximum RGBA8 difference: "+difference);
    }
    private static void save(ByteBuffer pixels,Path path) throws Exception { var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB); for(int y=0;y<H;y++) for(int x=0;x<W;x++) { int i=(y*W+x)*4; image.setRGB(x,H-1-y,(pixels.get(i)&255)<<16|(pixels.get(i+1)&255)<<8|(pixels.get(i+2)&255)); } ImageIO.write(image,"PNG",path.toFile()); }
}
