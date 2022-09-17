package arc.audio;

import arc.files.*;

public interface DownloadableAudio {

    void load(Fi file);

    default void loadDirectly(Fi dest){
        load(dest);
    }
}
