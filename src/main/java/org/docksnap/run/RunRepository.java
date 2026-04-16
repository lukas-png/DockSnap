package org.docksnap.run;

import org.docksnap.domain.Run;

import java.util.List;

public interface RunRepository {
    void save(Run run);
    Run find(String id);
    List<Run> list();
    void update(Run run);
}
