package ph.opendtv.bundle;

import ph.opendtv.ota.AppLogic;

/**
 * App logic that updates over the air (see app/src/main/java/ph/opendtv/ota/LogicBundles.java).
 * Build with tools/ota/build_bundle.sh; bump VERSION for every published bundle.
 *
 * Bundle 1 is the one inside the APK and does exactly what the app did before bundles: a later bundle changes
 * behaviour, not this one.
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
        return listedServiceId == serviceId;   // the full scan's rule so far (issue #1 is about changing it)
    }

    @Override
    public String logoFor(String channelName, String networkName) {
        return null;   // the app's own rules (ui/LogoHelper) decide
    }
}
