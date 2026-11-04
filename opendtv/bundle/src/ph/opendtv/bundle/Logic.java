package ph.opendtv.bundle;

import ph.opendtv.ota.AppLogic;

/**
 * App logic that updates over the air (see app/src/main/java/ph/opendtv/ota/LogicBundles.java).
 * Build with tools/ota/build_bundle.sh; bump VERSION for every published bundle.
 */
public final class Logic implements AppLogic {
    private static final int VERSION = 1;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public boolean isSameService(int listedServiceId, int listedTsId, int listedNetworkId,
                                 int serviceId, int tsId, int networkId) {
        // Bundle 1: the rule the released app has today. Any service whose ID is already listed counts as a
        // duplicate, on any frequency.
        return listedServiceId == serviceId;
    }
}
