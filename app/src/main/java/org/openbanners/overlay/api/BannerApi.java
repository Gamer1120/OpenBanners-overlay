package org.openbanners.overlay.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public class BannerApi {
    private static final OkHttpClient client = new OkHttpClient.Builder().build();

    private static final ObjectMapper objectMapper;

    private static final String baseUrl = "https://api.bannergress.com";

    static {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static List<Banner> findBanners(String missionId) throws IOException {
        HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(baseUrl)).newBuilder();
        urlBuilder.addPathSegment("bnrs");
        urlBuilder.addQueryParameter("missionId", missionId);
        Request request = new Request.Builder().url(urlBuilder.build()).build();
        ResponseBody responseBody = client.newCall(request).execute().body();
        assert responseBody != null;
        return objectMapper.readValue(responseBody.string(), new TypeReference<List<Banner>>() {
        });
    }

    /**
     * The banner's API URL. The id is the decoded slug (e.g. st-paulus-dom-münster-4d07) and gets percent-encoded
     * exactly once here: .../bnrs/st-paulus-dom-m%C3%BCnster-4d07.
     */
    public static HttpUrl bannerUrl(String bannerId) {
        return Objects.requireNonNull(HttpUrl.parse(baseUrl)).newBuilder()
                .addPathSegment("bnrs")
                .addPathSegment(bannerId)
                .build();
    }

    public static Banner getBanner(String bannerId) throws IOException {
        Request request = new Request.Builder().url(bannerUrl(bannerId)).build();
        ResponseBody responseBody = client.newCall(request).execute().body();
        assert responseBody != null;
        return objectMapper.readValue(responseBody.string(), Banner.class);
    }
}
