package io.github.xbigtv.tv.xapi;

import java.util.ArrayList;
import java.util.List;

public final class TimelinePage {

    public final List<Post> posts = new ArrayList<>();
    public String bottomCursor = "";
    public String error = "";

    public boolean ok() {
        return error == null || error.isEmpty();
    }
}
