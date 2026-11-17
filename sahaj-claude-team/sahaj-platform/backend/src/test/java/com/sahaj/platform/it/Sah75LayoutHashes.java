package com.sahaj.platform.it;

import java.util.Map;
import java.util.TreeMap;

/**
 * Pinned md5 of the stored layout text of the 30 starter layout documents of version 1 and 2 (SAH-75 database cases 6 and 15),
 * read from sahaj_test after V2026_12_14_1000 to 1060 were applied (15 + 15 rows, immutable by trigger). Version 1 and 2 must stay
 * byte for byte as they are: a later migration or a repair that touched one would change its hash here.
 */
final class Sah75LayoutHashes {

    static final Map<String, String> V1_V2 = new TreeMap<>();

    static {
        V1_V2.put("ayush/v1", "4bd16229157e3098057a7c8f474212ea");
        V1_V2.put("ayush/v2", "372fd731b280ecb7fe47af3ef59f8584");
        V1_V2.put("cardio/v1", "3688cfa61742f51df38c88f221e0fa1c");
        V1_V2.put("cardio/v2", "9b13dfb4c3e7e1aee853909e895e75bb");
        V1_V2.put("dental/v1", "9c51a6128c2d2791dc8fa2bf45a45f17");
        V1_V2.put("dental/v2", "2018699eb89c24d905ef85c5a0a8bdee");
        V1_V2.put("derm/v1", "2d85c34b4d8a349271f2245a3bdd1c6e");
        V1_V2.put("derm/v2", "02c5ff0d359acced4e35c6184e743162");
        V1_V2.put("diab/v1", "7b70e5e7d3005ebc45da949189df5402");
        V1_V2.put("diab/v2", "92117d349e3a28e38b0c4e481db7cfa0");
        V1_V2.put("ent/v1", "396cf7133e80fdca516aa915a03a56d2");
        V1_V2.put("ent/v2", "5c729cb21297f341afdbe27a0d0efdd8");
        V1_V2.put("eye/v1", "bd6646bd24ec7ebaa48736dab89159a8");
        V1_V2.put("eye/v2", "2bffbd5771301e9cb7db1bdc92314843");
        V1_V2.put("gm/v1", "57a2e67edfcc9fa3a667f069071f0114");
        V1_V2.put("gm/v2", "ab90fc5bdee60a3ea0c26943c2c5c96e");
        V1_V2.put("og/v1", "1cc3b1f539c1e87cf4fc2aa2709cb7c2");
        V1_V2.put("og/v2", "0765ccf23453adb69eb0cc7ebb225bad");
        V1_V2.put("onco/v1", "db06cf56c118bdf0c263adfb9acdc3de");
        V1_V2.put("onco/v2", "fc488a16ff55db681ae288b10d60d7e1");
        V1_V2.put("ortho/v1", "b38f28f761578259792e95a1fb9d51d1");
        V1_V2.put("ortho/v2", "022b01d37419073e7046f4bc68e86306");
        V1_V2.put("paeds/v1", "6f9b827d0beb6d9f597424ab04e30b99");
        V1_V2.put("paeds/v2", "c66bbbaa687beba06721169232b2ec55");
        V1_V2.put("physio/v1", "73e7ffe4c64c66d009eabd7ca4e8b0ce");
        V1_V2.put("physio/v2", "73e8faab2f44b1e70ddd51a3c92b4817");
        V1_V2.put("psych/v1", "053a26145231e31669099c0eca9f7057");
        V1_V2.put("psych/v2", "bef7173ae6c4054bad1e97cb99433e88");
        V1_V2.put("surgery/v1", "d40acc359be0b58ec6e76efbe1ab637c");
        V1_V2.put("surgery/v2", "09f3746a7ba9929b79dc488c923a624f");
    }

    private Sah75LayoutHashes() {
    }
}
