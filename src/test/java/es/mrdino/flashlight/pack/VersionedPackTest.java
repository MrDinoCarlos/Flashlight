package es.mrdino.flashlight.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Compile and link every shipped renderer against its exact vanilla include API. */
class VersionedPackTest {
    private static long window;
    private static final Pattern IMPORT=Pattern.compile("#(?:moj_import|include) <(?:(\\w+):)?([^>]+)>");

    @BeforeAll static void context() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);
        window=glfwCreateWindow(64,64,"Versioned resource packs",0,0); assertNotEquals(0,window);
        glfwMakeContextCurrent(window); GL.createCapabilities();
    }
    @AfterAll static void close() { GL.setCapabilities(null); glfwDestroyWindow(window); glfwTerminate(); }

    private static String version() throws Exception {
        var matcher=Pattern.compile("(?m)^version = \"([^\"]+)\"").matcher(Files.readString(Path.of("build.gradle.kts")));
        assertTrue(matcher.find()); return matcher.group(1);
    }
    private static Map<String,byte[]> pack(String minecraft) throws Exception {
        String branch=minecraft.startsWith("1.21.") ? "1.21.X" : minecraft.substring(0,4)+".X";
        var path=Path.of("build/distributions/FlashLight-ResourcePack-"+version()+"-"+branch+".zip");
        return ShaderComposer.read(PackCompatibility.forMinecraft(Files.readAllBytes(path),minecraft));
    }
    private static String text(Map<String,byte[]> pack,String path) { return new String(pack.get(path),StandardCharsets.UTF_8); }
    private static JsonObject json(Map<String,byte[]> pack,String path) { return JsonParser.parseString(text(pack,path)).getAsJsonObject(); }
    private static String expand(Map<String,byte[]> pack,String minecraft,String content) throws Exception {
        var result=new StringBuilder();
        for(String line:content.lines().toList()) {
            var matcher=IMPORT.matcher(line.trim());
            if(matcher.matches()) {
                String namespace=matcher.group(1)==null ? "minecraft" : matcher.group(1);
                String path="assets/"+namespace+"/shaders/include/"+matcher.group(2);
                String included=pack.containsKey(path) ? text(pack,path) : Files.readString(Path.of("src/test/resources/compat/versions",minecraft,path));
                result.append(expand(pack,minecraft,included).replaceAll("(?m)^#version[^\\r\\n]*", ""));
            } else result.append(line).append('\n');
        }
        return result.toString();
    }
    private static String source(Map<String,byte[]> pack,String minecraft,String path,String defines) throws Exception {
        String raw=pack.containsKey(path) ? text(pack,path) : Files.readString(Path.of("src/test/resources/compat/versions",minecraft,path));
        String expanded=expand(pack,minecraft,raw);
        int end=expanded.indexOf('\n');
        return expanded.substring(0,end+1)+defines+expanded.substring(end+1);
    }
    private static int program(Map<String,byte[]> pack,String minecraft,String vertex,String fragment,String defines) throws Exception {
        int program=glCreateProgram();
        try {
            for(String path:List.of(vertex,fragment)) {
                int shader=glCreateShader(path.endsWith(".vsh") ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
                String content=source(pack,minecraft,path,defines);
                // 26.3 uses Vulkan semantics in ShaderC even on its OpenGL backend.
                // The driver GLSL link below additionally verifies varying interfaces.
                content=content.replace("gl_VertexIndex","gl_VertexID");
                glShaderSource(shader,content); glCompileShader(shader);
                assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),minecraft+" "+path+" "+defines+": "+glGetShaderInfoLog(shader));
                glAttachShader(program,shader); glDeleteShader(shader);
            }
            glLinkProgram(program);
            assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),minecraft+" "+vertex+": "+glGetProgramInfoLog(program));
            return program;
        } catch(Throwable failure) { glDeleteProgram(program); throw failure; }
    }
    private static void link(Map<String,byte[]> pack,String minecraft,String vertex,String fragment,String defines) throws Exception {
        glDeleteProgram(program(pack,minecraft,vertex,fragment,defines));
    }
    private static void spirv(Map<String,byte[]> pack,String minecraft,String path,String defines) throws Exception {
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize();
        try {
            // Matches 26.3 GlslCompiler: Vulkan semantics, automatic bindings.
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options,true);
            String content=source(pack,minecraft,path,defines);
            long result=shaderc_compile_into_spv(compiler,content,path.endsWith(".vsh") ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader,path,"main",options);
            try {
                assertEquals(shaderc_compilation_status_success,shaderc_result_get_compilation_status(result),path+" "+defines+": "+shaderc_result_get_error_message(result));
            } finally { shaderc_result_release(result); }
        } finally { shaderc_compile_options_release(options); shaderc_compiler_release(compiler); }
    }

    @ParameterizedTest
    @ValueSource(strings={"1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2","26.2","26.3"})
    void shippedPackUsesTheNativeSchemaAndLinksAgainstThatClientsUniforms(String minecraft) throws Exception {
        var pack=pack(minecraft);
        var metadata=json(pack,"pack.mcmeta").getAsJsonObject("pack");
        assertEquals(PackCompatibility.format(minecraft),PackCompatibility.major(metadata.get("min_format")));
        assertTrue(pack.keySet().stream().noneMatch(path->path.startsWith("mc_")),"Export resolves native overlays before integration");
        assertTrue(pack.containsKey("assets/flashlight/items/flashlight_on.json"));
        var graph=json(pack,"assets/minecraft/post_effect/entity_outline.json");
        var targets=new HashSet<>(graph.getAsJsonObject("targets").keySet());
        targets.addAll(List.of("minecraft:main","minecraft:entity_outline"));
        assertEquals(5,graph.getAsJsonArray("passes").size());
        for(var element:graph.getAsJsonArray("passes")) {
            var pass=element.getAsJsonObject();
            String output=pass.get("output").getAsString(); assertTrue(targets.contains(output));
            assertNotEquals("minecraft:main",output,"Keep the main depth buffer intact");
            for(var input:pass.getAsJsonArray("inputs")) {
                String target=input.getAsJsonObject().get("target").getAsString();
                assertTrue(targets.contains(target)); assertNotEquals(output,target);
            }
            String vertex,fragment;
            if(minecraft.equals("1.21.4")) {
                assertTrue(pass.has("program"));
                String id=pass.get("program").getAsString();
                String programPath="assets/"+id.replace(":","/shaders/")+".json";
                var program=pack.containsKey(programPath) ? json(pack,programPath)
                        : JsonParser.parseString(Files.readString(Path.of("src/test/resources/compat/versions",minecraft,programPath))).getAsJsonObject();
                vertex=program.get("vertex").getAsString(); fragment=program.get("fragment").getAsString();
            } else {
                assertFalse(pass.has("program"));
                vertex=pass.get("vertex_shader").getAsString(); fragment=pass.get("fragment_shader").getAsString();
                if(pass.has("uniforms")) assertEquals(!minecraft.equals("1.21.5"),pass.get("uniforms").isJsonObject());
            }
            String v="assets/"+vertex.replace(":","/shaders/")+".vsh",f="assets/"+fragment.replace(":","/shaders/")+".fsh";
            if(minecraft.equals("26.3")) { spirv(pack,minecraft,v,""); spirv(pack,minecraft,f,""); }
            // Native screenquad is not shipped in the pack, pin it from the client.
            link(pack,minecraft,v,f,"");
        }
        var defines=new ArrayList<>(List.of("", "#define ALPHA_CUTOUT 0.1\n", "#define NO_CARDINAL_LIGHTING\n#define EMISSIVE\n#define NO_OVERLAY\n"));
        if(minecraft.startsWith("26.")) defines.add("#define PER_FACE_LIGHTING\n#define DISSOLVE\n");
        for(String core:List.of("entity",minecraft.startsWith("1.") ? "rendertype_item_entity_translucent_cull" : "item","rendertype_outline")) {
            String vertex="assets/minecraft/shaders/core/"+core+".vsh",fragment="assets/minecraft/shaders/core/"+core+".fsh";
            for(String flags:defines) {
                if(minecraft.equals("26.3")) { spirv(pack,minecraft,vertex,flags); spirv(pack,minecraft,fragment,flags); }
                link(pack,minecraft,vertex,fragment,flags);
            }
        }
        if(minecraft.equals("26.3")) {
            String common="#define OIT\n#define OIT_COEFF_COUNT 8\n#define OIT_WAVELET_RANK 2\n#define OIT_COEFF_ATTACHMENT_COUNT 2\n#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n";
            for(String phase:List.of("#define OIT_ALPHA_ONLY\n#define OIT_DEPTH_BOUNDS\n", "#define OIT_ALPHA_ONLY\n#define OIT_TRANSMITTANCE\n", "#define OIT_ACCUMULATE\n")) {
                for(String core:List.of("entity","item")) for(String suffix:List.of("vsh","fsh"))
                    spirv(pack,minecraft,"assets/minecraft/shaders/core/"+core+"."+suffix,common+phase);
            }
        }
        try(var renderer=new NativeProjection(pack,minecraft)) { renderer.check(); }
        if(minecraft.equals("26.3")) {
            org.lwjgl.opengl.GL45C.glClipControl(org.lwjgl.opengl.GL45C.GL_LOWER_LEFT,org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE);
            try(var renderer=new NativeProjection(pack,minecraft,true)) { renderer.check(); }
            finally { org.lwjgl.opengl.GL45C.glClipControl(org.lwjgl.opengl.GL45C.GL_LOWER_LEFT,org.lwjgl.opengl.GL45C.GL_NEGATIVE_ONE_TO_ONE); }
        }
        assertEquals(GL_NO_ERROR,glGetError());
    }

    @Test void formatsOutsideTheRequestedRangeAreRejected() {
        for(String version:List.of("1.21.3","26.4","26.3-snapshot-1"))
            assertThrows(IllegalArgumentException.class,()->PackCompatibility.format(version));
    }

    /** Executes the real graph, including each version's actual UBO byte layout. */
    private static final class NativeProjection implements AutoCloseable {
        private static final int SIZE=192;
        private record Target(int framebuffer,int texture,int depth,int width,int height) { }
        final Map<String,byte[]> assets;
        final String minecraft;
        final boolean zeroToOne;
        final JsonObject graph;
        final Map<String,Target> targets=new java.util.LinkedHashMap<>();
        final List<Integer> programs=new ArrayList<>(),textures=new ArrayList<>(),buffers=new ArrayList<>(),fbos=new ArrayList<>();
        final List<Integer> stages=new ArrayList<>();
        final Matrix4f projection=new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,128);
        final int vao=glGenVertexArrays(),vbo=glGenBuffers(),ebo=glGenBuffers(),outline,marker;
        NativeProjection(Map<String,byte[]> assets,String minecraft) throws Exception { this(assets,minecraft,false); }
        NativeProjection(Map<String,byte[]> assets,String minecraft,boolean zeroToOne) throws Exception {
            this.assets=assets; this.minecraft=minecraft; this.zeroToOne=zeroToOne;
            if(zeroToOne) projection.setPerspective((float)Math.toRadians(70),1,.05f,128,true);
            graph=json(assets,"assets/minecraft/post_effect/entity_outline.json");
            targets.put("minecraft:main",target(SIZE,SIZE)); targets.put("minecraft:entity_outline",target(SIZE,SIZE));
            for(var entry:graph.getAsJsonObject("targets").entrySet()) {
                var dimensions=entry.getValue().getAsJsonObject();
                targets.put(entry.getKey(),target(dimensions.has("width") ? dimensions.get("width").getAsInt() : SIZE,
                        dimensions.has("height") ? dimensions.get("height").getAsInt() : SIZE));
            }
            outline=compile("assets/minecraft/shaders/core/rendertype_outline.vsh","assets/minecraft/shaders/core/rendertype_outline.fsh");
            for(var element:graph.getAsJsonArray("passes")) {
                var stage=element.getAsJsonObject(); String vertex,fragment;
                if(stage.has("program")) {
                    String programPath="assets/"+stage.get("program").getAsString().replace(":","/shaders/")+".json";
                    var config=assets.containsKey(programPath) ? json(assets,programPath)
                            : JsonParser.parseString(Files.readString(Path.of("src/test/resources/compat/versions",minecraft,programPath))).getAsJsonObject();
                    vertex=config.get("vertex").getAsString(); fragment=config.get("fragment").getAsString();
                } else { vertex=stage.get("vertex_shader").getAsString(); fragment=stage.get("fragment_shader").getAsString(); }
                stages.add(compile("assets/"+vertex.replace(":","/shaders/")+".vsh","assets/"+fragment.replace(":","/shaders/")+".fsh"));
            }
            var image=ImageIO.read(new java.io.ByteArrayInputStream(assets.get("assets/flashlight/textures/item/beam/entity_control.png")));
            marker=texture(GL_RGBA8,GL_RGBA,16,16);
            ByteBuffer pixels=BufferUtils.createByteBuffer(16*16*4);
            for(int y=0;y<16;y++) for(int x=0;x<16;x++) { int p=image.getRGB(x,y); pixels.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)(p>>>24)); }
            pixels.flip(); glTexSubImage2D(GL_TEXTURE_2D,0,0,0,16,16,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo); glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{0,1,2,0,2,3},GL_STATIC_DRAW);
        }
        int compile(String vertex,String fragment) throws Exception { int p=program(assets,minecraft,vertex,fragment,zeroToOne ? "#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n" : ""); programs.add(p); return p; }
        int texture(int format,int type,int width,int height) {
            int texture=glGenTextures(); textures.add(texture); glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,format,width,height,0,type,GL_FLOAT,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE); return texture;
        }
        Target target(int width,int height) {
            int framebuffer=glGenFramebuffers(); fbos.add(framebuffer);
            int color=texture(GL_RGBA8,GL_RGBA,width,height),depth=texture(GL_DEPTH_COMPONENT24,GL_DEPTH_COMPONENT,width,height);
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER)); return new Target(framebuffer,color,depth,width,height);
        }
        void bind(int program,String name,int unit,int texture) { glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,texture); glUniform1i(glGetUniformLocation(program,name),unit); }
        void clear(Target target) { glBindFramebuffer(GL_FRAMEBUFFER,target.framebuffer); glViewport(0,0,target.width,target.height); glDepthMask(true); glClearColor(0,0,0,0); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT); }
        void uniforms(int program,Matrix4f projection,int width,int height,float blurX,float blurY) {
            glUseProgram(program);
            Map<String,float[]> values=Map.ofEntries(Map.entry("ModelViewMat",new Matrix4f().get(new float[16])),Map.entry("TextureMat",new Matrix4f().get(new float[16])),
                    Map.entry("ProjMat",projection.get(new float[16])),Map.entry("ScreenSize",new float[]{SIZE,SIZE}),Map.entry("OutSize",new float[]{width,height}),
                    Map.entry("InSize",new float[]{SIZE,SIZE}),Map.entry("ColorModulator",new float[]{1,1,1,1}),Map.entry("BlurDir",new float[]{blurX,blurY}),Map.entry("Radius",new float[]{2}));
            for(var entry:values.entrySet()) {
                int location=glGetUniformLocation(program,entry.getKey()); if(location<0) continue;
                float[] value=entry.getValue();
                if(value.length==16) glUniformMatrix4fv(location,false,value);
                else if(value.length==4) glUniform4fv(location,value);
                else if(value.length==2) glUniform2fv(location,value);
                else glUniform1fv(location,value);
            }
            int binding=0;
            for(String block:List.of("Projection","DynamicTransforms","Globals","SamplerInfo","BlurConfig")) {
                int index=glGetUniformBlockIndex(program,block); if(index==GL_INVALID_INDEX) continue;
                int size=glGetActiveUniformBlocki(program,index,GL_UNIFORM_BLOCK_DATA_SIZE);
                ByteBuffer bytes=BufferUtils.createByteBuffer(size).order(ByteOrder.nativeOrder());
                for(var entry:values.entrySet()) {
                    int uniform=glGetUniformIndices(program,entry.getKey());
                    if(uniform==GL_INVALID_INDEX || glGetActiveUniformsi(program,uniform,GL_UNIFORM_BLOCK_INDEX)!=index) continue;
                    int offset=glGetActiveUniformsi(program,uniform,GL_UNIFORM_OFFSET);
                    for(float value:entry.getValue()) { bytes.putFloat(offset,value); offset+=4; }
                }
                int buffer=glGenBuffers(); buffers.add(buffer); glBindBuffer(GL_UNIFORM_BUFFER,buffer); glBufferData(GL_UNIFORM_BUFFER,bytes,GL_STREAM_DRAW);
                glUniformBlockBinding(program,index,binding); glBindBufferBase(GL_UNIFORM_BUFFER,binding++,buffer);
            }
        }
        void draw(int program,float[] positions) {
            glBindVertexArray(vao);
            int position=glGetAttribLocation(program,"Position"),uv=glGetAttribLocation(program,"UV0");
            if(position<0) { glDrawArrays(GL_TRIANGLES,0,3); return; }
            var vertices=BufferUtils.createFloatBuffer(20); float[] tex={0,0,0,1,1,1,1,0};
            for(int i=0;i<4;i++) vertices.put(positions,i*3,3).put(tex,i*2,2); vertices.flip();
            glBindBuffer(GL_ARRAY_BUFFER,vbo); glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            glEnableVertexAttribArray(position); glVertexAttribPointer(position,3,GL_FLOAT,false,20,0L);
            if(uv>=0) { glEnableVertexAttribArray(uv); glVertexAttribPointer(uv,2,GL_FLOAT,false,20,12L); }
            glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_INT,0L); glDisableVertexAttribArray(position); if(uv>=0) glDisableVertexAttribArray(uv);
        }
        ByteBuffer frame(double power) throws Exception {
            Target scene=targets.get("minecraft:main"),data=targets.get("minecraft:entity_outline");
            clear(scene); glClearColor(.06f,.045f,.03f,1);
            var position=projection.transform(new org.joml.Vector4f(0,0,-8,1)); glClearDepth(zeroToOne ? position.z/position.w : position.z/position.w*.5+.5); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
            float[] depth=new float[SIZE*SIZE]; glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            clear(data); glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            glUseProgram(outline); uniforms(outline,projection,SIZE,SIZE,0,0); bind(outline,"Sampler0",0,marker);
            int color=glGetAttribLocation(outline,"Color"),word=(int)Math.round(power*255)<<5 | 16<<13 | 24<<18;
            glDisableVertexAttribArray(color); glVertexAttrib4f(color,(word&255)/255f,(word>>>8&255)/255f,(word>>>16&255)/255f,1);
            draw(outline,new float[]{1,10,0,1,-10,0,-1,-10,0,-1,10,0});
            int stageIndex=0;
            for(var element:graph.getAsJsonArray("passes")) {
                var stage=element.getAsJsonObject(); Target output=targets.get(stage.get("output").getAsString());
                int program=stages.get(stageIndex++);
                clear(output); glUseProgram(program);
                uniforms(program,new Matrix4f().setOrtho(0,SIZE,0,SIZE,.1f,1000),output.width,output.height,stageIndex==3 ? 1 : 0,stageIndex==4 ? 1 : 0);
                int unit=0;
                for(var input:stage.getAsJsonArray("inputs")) {
                    var entry=input.getAsJsonObject(); Target source=targets.get(entry.get("target").getAsString());
                    bind(program,entry.get("sampler_name").getAsString()+"Sampler",unit++,entry.has("use_depth_buffer") ? source.depth : source.texture);
                }
                float width=minecraft.equals("1.21.4") ? output.width : 1,height=minecraft.equals("1.21.4") ? output.height : 1;
                draw(program,new float[]{0,height,0,0,0,0,width,0,0,width,height,0});
            }
            glBindFramebuffer(GL_FRAMEBUFFER,scene.framebuffer); float[] after=new float[SIZE*SIZE]; glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,after);
            assertArrayEquals(depth,after,"Native post passes retain the scene depth on "+minecraft);
            glBindFramebuffer(GL_FRAMEBUFFER,data.framebuffer); ByteBuffer result=BufferUtils.createByteBuffer(SIZE*SIZE*4); glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,result); return result;
        }
        void check() throws Exception {
            ByteBuffer on=frame(.8),off=frame(0),dim=frame(.12);
            int center=(SIZE/2*SIZE+SIZE/2)*4;
            assertTrue((on.get(center+3)&255)>0,minecraft+" executes the native graph and lights the wall");
            assertEquals(0,off.get(center+3)&255,minecraft+" has no residual light at power zero");
            assertTrue((dim.get(center+3)&255)>0,minecraft+" transports partial intensity through its native UBOs");
            assertEquals(0,on.get((SIZE*10+180)*4+3)&255,minecraft+" keeps pixels outside the cone unchanged");
        }
        @Override public void close() { programs.forEach(org.lwjgl.opengl.GL33C::glDeleteProgram); textures.forEach(org.lwjgl.opengl.GL33C::glDeleteTextures); buffers.forEach(org.lwjgl.opengl.GL33C::glDeleteBuffers); fbos.forEach(org.lwjgl.opengl.GL33C::glDeleteFramebuffers); glDeleteBuffers(vbo); glDeleteBuffers(ebo); glDeleteVertexArrays(vao); }
    }
}
