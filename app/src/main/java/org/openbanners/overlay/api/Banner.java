package org.openbanners.overlay.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.SortedMap;

public class Banner {
    public String id;

    public String title;

    public SortedMap<Integer, Mission> missions;

    /** Set when this is a Mission Day plan (see {@link PlanApi}) instead of a Bannergress banner; {@link #id} is null then. */
    @JsonIgnore
    public String planId;

    /** The OpenBanners site the plan lives on, e.g. https://openbanners.org. */
    @JsonIgnore
    public String planSite;
}
