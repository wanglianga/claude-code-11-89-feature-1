package com.waterrepair.construction.geom;

/**
 * 平面坐标点。样例数据使用“局部工程坐标（米）”录入，x 为东向、y 为北向；
 * 全部几何判断（距离、相交、缓冲区）均在同一工程坐标系下完成，避免经纬度球面距离带来的歧义。
 */
public record GeoPoint(double x, double y) {

    public double distanceTo(GeoPoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public GeoPoint plus(double dx, double dy) {
        return new GeoPoint(x + dx, y + dy);
    }
}
