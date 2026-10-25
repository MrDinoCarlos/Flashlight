package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;
import org.bukkit.Color;
import org.bukkit.entity.Display;

/** Small, private marker variants carry ellipse orientation; tint/light carry its centre and power. */
public record SurfaceProjection(String model, Color color, Display.Brightness brightness) {
    public static final int ASPECT_BANDS=16, ANGLE_BANDS=32;
    public static double diameter(int code) { return .125*Math.pow(512,code/255.0); }
    public static double aspect(int code) { return Math.pow(16,code/15.0); }

    /** Hysteresis only affects codec boundaries; aiming and commanded flicker stay immediate. */
    public static final class State {
        private int aspect=-1,angle=-1,diameter=-1;
        private boolean hyperbola;
        private double fade=-1;
        private long seen=Long.MIN_VALUE;
        void prepareFade(double target,long tick) {
            long elapsed=tick-seen;
            fade=fade<0 || elapsed>10 || elapsed<0 ? target : fade+(target-fade)*(1-Math.pow(.5,Math.max(0,elapsed)));
            seen=tick;
        }
        long lastSeen() { return seen; }
        private void mode(boolean hyperbola) {
            if(this.hyperbola!=hyperbola) { aspect=angle=diameter=-1; this.hyperbola=hyperbola; }
        }
        private static int step(int previous,double wanted,int size,boolean cyclic) {
            double delta=wanted-previous;
            if(cyclic) delta-=Math.floor(delta/size+.5)*size;
            if(previous>=0 && Math.abs(delta)<=.70) return previous;
            int rounded=(int)Math.round(wanted);
            return cyclic ? Math.floorMod(rounded,size) : Math.clamp(rounded,0,size-1);
        }
    }

    public static SurfaceProjection of(SurfacePatch face,Vec3 source,Vec3 direction,double lensRadius,
                                       double angle,double range,double power) {
        return of(face,source,direction,lensRadius,angle,range,power,null);
    }

    public static SurfaceProjection of(SurfacePatch face,Vec3 source,Vec3 direction,double lensRadius,
                                       double angle,double range,double power,State state) {
        double slope=Math.tan(Math.toRadians(angle*.5));
        Vec3 tip=source.add(direction.scale(-lensRadius/slope));
        double incidence=-SurfacePatch.dot(direction,face.normal());
        double h=SurfacePatch.dot(tip,face.normal())-face.plane();
        if(h<=0) return null;
        double cosSquared=1/(1+slope*slope);
        double planar=Math.sqrt(Math.max(0,1-incidence*incidence));
        double lambda=1-planar*planar/cosSquared;
        boolean hyperbola=lambda<0;
        if(state!=null) state.mode(hyperbola);
        lambda=Math.copySign(Math.max(1.0/256,Math.abs(lambda)),lambda);
        double rawDiameter=2*h*slope/Math.sqrt(Math.abs(lambda));
        double wantedDiameter=Math.clamp(Math.log(rawDiameter/.125)/Math.log(512)*255,0,255);
        int d=state==null ? (int)Math.round(wantedDiameter) : (state.diameter=State.step(state.diameter,wantedDiameter,256,false));
        double diameter=diameter(d);
        double wantedAspect=Math.clamp(Math.log(1/Math.sqrt(Math.abs(lambda)))/Math.log(16)*15,0,15);
        int a=state==null ? (int)Math.round(wantedAspect) : (state.aspect=State.step(state.aspect,wantedAspect,16,false));
        double theta=Math.atan2(SurfacePatch.dot(direction,face.v()),SurfacePatch.dot(direction,face.u()));
        double period=hyperbola ? 2*Math.PI : Math.PI;
        theta=(theta%period+period)%period;
        double wantedAngle=theta/period*ANGLE_BANDS;
        int r=state==null ? ((int)Math.round(wantedAngle))%ANGLE_BANDS : (state.angle=State.step(state.angle,wantedAngle,ANGLE_BANDS,true));
        double rotation=r*period/ANGLE_BANDS, c=Math.cos(rotation),s=Math.sin(rotation);
        Vec3 tangent=direction.add(face.normal().scale(incidence));
        Vec3 centre=tip.add(face.normal().scale(-h));
        if(planar>1e-6) centre=centre.add(tangent.scale(incidence*h/(cosSquared*lambda)));
        Vec3 delta=face.center().add(centre.scale(-1));
        double x=SurfacePatch.dot(delta,face.u()),y=SurfacePatch.dot(delta,face.v());
        double u=(c*x+s*y)/(diameter*aspect(a))*2,v=(-s*x+c*y)/diameter*2;
        int red=hyperbola ? logarithmic(u) : channel(.5+u*.5),green=hyperbola ? logarithmic(v) : channel(.5+v*.5);
        double fade=state!=null && state.fade>=0 ? state.fade : attenuation(face,face.center(),source,direction,range);
        int intensity=(int)Math.round(255*Math.clamp(power*fade,0,1));
        return new SurfaceProjection("surface_"+(hyperbola ? "h_" : "")+a+"_"+r,Color.fromRGB(red,green,d),
                new Display.Brightness(intensity&15,intensity>>>4));
    }
    static double attenuation(SurfacePatch face,Vec3 representative,Vec3 source,Vec3 direction,double range) {
        double distance=Math.sqrt(representative.add(source.scale(-1)).lengthSquared());
        double incidence=-SurfacePatch.dot(direction,face.normal());
        double axis=incidence>1e-6 ? (SurfacePatch.dot(source,face.normal())-face.plane())/incidence : -1;
        if(axis>0 && axis<range) {
            double edge=Math.clamp((axis/range-.75)/.25,0,1);
            double weight=1-edge*edge*(3-2*edge);
            distance+=(axis-distance)*weight;
        }
        // A cone grazing a side face spreads its energy over a larger area.
        // Without incidence falloff each face creates an equally bright hot spot.
        double planeDistance=Math.max(0,SurfacePatch.dot(source,face.normal())-face.plane());
        double incidenceFade=Math.sqrt(Math.clamp(planeDistance/Math.max(.001,distance),0,1));
        return BeamOptics.attenuation(distance,range)*incidenceFade;
    }
    private static int channel(double value) { return (int)Math.round(Math.clamp((value+.5)*.5,0,1)*255); }
    private static int logarithmic(double value) {
        double encoded=Math.copySign(Math.log1p(Math.abs(value))/Math.log(65),value);
        return (int)Math.round(Math.clamp(.5+encoded*.5,0,1)*255);
    }
}
