package org.openbanners.overlay.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

public class Mission {
    public String id;

    public List<MissionStep> steps;

    public MissionType type;

    /**
     * The Bannergress banner this mission belongs to, and its 0-based number there. Set by {@link BannerApi#getBanner};
     * in a Mission Day plan the mission's place in the plan differs, but its passphrases come from this banner and
     * "the mission number" is this one.
     */
    @JsonIgnore
    public String sourceBannerId;

    @JsonIgnore
    public int sourceIndex = -1;
}
