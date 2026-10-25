package es.mrdino.flashlight.light;

import java.util.Arrays;
import java.util.List;

/** Preserve a world's receiving faces when ray order, merging or distance order changes. */
final class SurfaceAssignments {
    static int[] match(List<SurfacePatch> previous,List<SurfacePatch> current) {
        int[] result=new int[current.size()]; Arrays.fill(result,-1);
        boolean[] used=new boolean[previous.size()];
        // Reserve every exact match before a changed rectangle can take its slot.
        for(int face=0;face<current.size();face++) for(int slot=0;slot<previous.size();slot++)
            if(!used[slot] && current.get(face).sameFootprint(previous.get(slot))) {
                result[face]=slot; used[slot]=true; break;
            }
        for(int face=0;face<current.size();face++) if(result[face]<0) {
            double best=0; int choice=-1;
            for(int slot=0;slot<previous.size();slot++) if(!used[slot]) {
                double overlap=current.get(face).overlap(previous.get(slot));
                if(overlap>best) { best=overlap; choice=slot; }
            }
            if(choice>=0) { result[face]=choice; used[choice]=true; }
        }
        int next=previous.size();
        for(int face=0;face<current.size();face++) if(result[face]<0) {
            for(int slot=0;slot<previous.size();slot++) if(!used[slot]) {
                result[face]=slot; used[slot]=true; break;
            }
            if(result[face]<0) result[face]=next++;
        }
        return result;
    }
}
