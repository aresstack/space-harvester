package com.aresstack.askai.research.search.dataforseo;

public enum DataForSeoOperatingSystem {
    WINDOWS("windows", DataForSeoDevice.DESKTOP),
    MACOS("macos", DataForSeoDevice.DESKTOP),
    ANDROID("android", DataForSeoDevice.MOBILE),
    IOS("ios", DataForSeoDevice.MOBILE);

    private final String apiValue;
    private final DataForSeoDevice device;

    DataForSeoOperatingSystem(
            String apiValue,
            DataForSeoDevice device) {

        this.apiValue = apiValue;
        this.device = device;
    }

    public String getApiValue() {
        return apiValue;
    }

    public DataForSeoDevice getDevice() {
        return device;
    }
}
