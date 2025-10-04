package cn.devcxl.photosync.ptp.db;

/**
 * Simple key-value holder. Previously extended SugarRecord; now a plain POJO.
 * Created by rainx on 2017/8/26.
 */
public class Uuid {
    String key;
    String value;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
