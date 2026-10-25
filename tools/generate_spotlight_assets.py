"""Resource-pack-only cone lighting using vanilla's outline chain (also in Fancy)."""
from pathlib import Path
import json
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
PACK=ROOT/'resource-pack'
IDENTITY=[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]
def write(path,document):
    target=PACK/path; target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
def target(name,sampler='In',depth=False):
    result={'sampler_name':sampler,'target':name}
    if depth: result['use_depth_buffer']=True
    return result
def uniform(name,kind,count,values): return {'name':name,'type':kind,'count':count,'values':values}
def generate(pack=None):
    for name,code in [('entity_control',11),('entity_control_right',12),('entity_control_left',13),('shadow_control',14)]:
        write(f'assets/flashlight/items/beam/{name}.json',{'model':{'type':'minecraft:model','model':f'flashlight:beam/{name}'}})
        write(f'assets/flashlight/models/beam/{name}.json',{'ambientocclusion':False,'textures':{'particle':f'flashlight:item/beam/{name}','control':f'flashlight:item/beam/{name}'},'elements':[{'from':[0,0,8],'to':[16,16,8],'faces':{'south':{'uv':[0,0,16,16],'texture':'#control'}}}]})
        image=PACK/f'assets/flashlight/textures/item/beam/{name}.png'; image.parent.mkdir(parents=True,exist_ok=True)
        pixels=Image.new('RGBA',(16,16),(code,49,197,100))
        for y in range(4,12):
            for x in range(4,12): pixels.putpixel((x,y),(166,12,0,255))
        pixels.save(image)
    write('assets/minecraft/shaders/core/rendertype_outline.json',{'vertex':'minecraft:core/rendertype_outline','fragment':'minecraft:core/rendertype_outline','samplers':[{'name':'Sampler0'}],
      'uniforms':[uniform('ModelViewMat','matrix4x4',16,IDENTITY),uniform('ProjMat','matrix4x4',16,IDENTITY),uniform('ColorModulator','float',4,[1,1,1,1]),uniform('ScreenSize','float',2,[1,1])]})
    for name,inputs in [('control_copy',['In']),('outline',['In']),('spotlight',['In','Depth','Data','Outline'])]:
        uniforms=[uniform('OutSize','float',2,[1,1])]
        if name!='control_copy': uniforms.insert(0,uniform('ProjMat','matrix4x4',16,IDENTITY))
        if name=='outline': uniforms.append(uniform('InSize','float',2,[1,1]))
        write(f'assets/flashlight/shaders/post/{name}.json',{'vertex':f'flashlight:post/{name}','fragment':f'flashlight:post/{name}',
          'samplers':[{'name':name+'Sampler'} for name in inputs], 'uniforms':uniforms})
    # Keep vanilla outline detection/blur, masking private data in the detector.
    # Only read minecraft:main: native PostPass clears its output
    # and cannot restore depth when depth testing is disabled. Let Minecraft's
    # normal alpha blit apply the combined light/outline without touching depth.
    passes=[
      {'program':'flashlight:post/control_copy','inputs':[target('minecraft:entity_outline')],'output':'flashlight_data'},
      {'program':'flashlight:post/outline','inputs':[target('minecraft:entity_outline')],'output':'swap'},
      {'program':'minecraft:post/entity_outline_box_blur','inputs':[dict(target('swap'),bilinear=True)],'output':'minecraft:entity_outline','uniforms':[{'name':'BlurDir','values':[1,0]}]},
      {'program':'minecraft:post/entity_outline_box_blur','inputs':[dict(target('minecraft:entity_outline'),bilinear=True)],'output':'swap','uniforms':[{'name':'BlurDir','values':[0,1]}]},
      {'program':'flashlight:post/spotlight','inputs':[target('minecraft:main'),target('minecraft:main','Depth',True),target('flashlight_data','Data'),target('swap','Outline')],'output':'minecraft:entity_outline'}]
    write('assets/minecraft/post_effect/entity_outline.json',{'targets':{'swap':{},'flashlight_data':{'width':27,'height':160}},'passes':passes})

if __name__=='__main__': generate()
