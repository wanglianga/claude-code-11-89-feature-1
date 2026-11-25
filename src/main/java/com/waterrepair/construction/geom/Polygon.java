package com.waterrepair.construction.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * 施工多边形（简单多边形，顶点按顺序录入、首尾不重复）。
 * 同时提供点包含、与折线段相交、到折线段的最近距离等几何原语。
 */
public record Polygon(List<GeoPoint> vertices) {

    public Polygon {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("施工多边形至少需要 3 个顶点");
        }
        vertices = List.copyOf(vertices);
    }

    /** 射线法判断点是否在多边形内部（边界点也算在内）。 */
    public boolean contains(GeoPoint p) {
        int n = vertices.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            GeoPoint a = vertices.get(j);
            GeoPoint b = vertices.get(i);
            if (distancePointToSegment(p, a, b) < 1e-9) {
                return true;
            }
            if ((a.y() > p.y()) != (b.y() > p.y())) {
                double crossX = a.x() + (p.y() - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (p.x() < crossX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** 折线段是否与多边形相交：任一端点在内部，或任一子线段穿越多边形边界。 */
    public boolean intersectsPolyline(List<GeoPoint> polyline) {
        for (GeoPoint p : polyline) {
            if (contains(p)) {
                return true;
            }
        }
        int n = vertices.size();
        for (int i = 0; i < polyline.size() - 1; i++) {
            GeoPoint a = polyline.get(i);
            GeoPoint b = polyline.get(i + 1);
            for (int k = 0; k < n; k++) {
                GeoPoint c = vertices.get(k);
                GeoPoint d = vertices.get((k + 1) % n);
                if (segmentsIntersect(a, b, c, d)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 多边形边界/内部到折线段的最近距离（米）。折线段穿入多边形时为 0。 */
    public double distanceToPolyline(List<GeoPoint> polyline) {
        if (intersectsPolyline(polyline)) {
            return 0.0;
        }
        double best = Double.POSITIVE_INFINITY;
        int n = vertices.size();
        for (GeoPoint p : polyline) {
            for (int k = 0; k < n; k++) {
                best = Math.min(best, distancePointToSegment(p, vertices.get(k), vertices.get((k + 1) % n)));
            }
        }
        for (int i = 0; i < polyline.size() - 1; i++) {
            for (GeoPoint v : vertices) {
                best = Math.min(best, distancePointToSegment(v, polyline.get(i), polyline.get(i + 1)));
            }
        }
        return best;
    }

    /** 多边形按给定米数向外（或向内，negative=true）平移边界，返回近似等距多边形。 */
    public Polygon buffer(double meters, boolean negative) {
        double amount = negative ? -meters : meters;
        boolean clockwise = signedArea() < 0;
        List<GeoPoint> out = new ArrayList<>(vertices.size());
        for (int i = 0; i < vertices.size(); i++) {
            GeoPoint prev = vertices.get((i - 1 + vertices.size()) % vertices.size());
            GeoPoint cur = vertices.get(i);
            GeoPoint next = vertices.get((i + 1) % vertices.size());
            // 两条邻边的外法线之和方向，作为该顶点的外扩方向（旋向无关）；
            // 斜接处按夹角补偿，使每条边恰好平移 amount 米，而非顶点仅移动 amount 米
            GeoPoint n1 = outwardNormal(prev, cur, clockwise);
            GeoPoint n2 = outwardNormal(cur, next, clockwise);
            double sx = n1.x() + n2.x();
            double sy = n1.y() + n2.y();
            double sLen = Math.hypot(sx, sy);
            if (sLen < 1e-9) {
                out.add(cur);
            } else {
                GeoPoint bisector = new GeoPoint(sx / sLen, sy / sLen);
                double miter = amount / Math.max(1e-9, n1.x() * bisector.x() + n1.y() * bisector.y());
                out.add(cur.plus(miter * bisector.x(), miter * bisector.y()));
            }
        }
        return new Polygon(out);
    }

    /** 有向边 a->b 的外法线（依据多边形旋向选择左/右侧法线）。 */
    private GeoPoint outwardNormal(GeoPoint a, GeoPoint b, boolean clockwise) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            return new GeoPoint(0, 0);
        }
        // 逆时针多边形内部在有向边左侧，外法线取右侧 (dy,-dx)；顺时针则取左侧 (-dy,dx)
        return clockwise ? new GeoPoint(-dy / len, dx / len) : new GeoPoint(dy / len, -dx / len);
    }

    private double signedArea() {
        double area = 0.0;
        for (int i = 0; i < vertices.size(); i++) {
            GeoPoint a = vertices.get(i);
            GeoPoint b = vertices.get((i + 1) % vertices.size());
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area / 2.0;
    }

    private static boolean segmentsIntersect(GeoPoint p1, GeoPoint p2, GeoPoint p3, GeoPoint p4) {
        double d1 = cross(p3, p4, p1);
        double d2 = cross(p3, p4, p2);
        double d3 = cross(p1, p2, p3);
        double d4 = cross(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return d1 == 0 && onSegment(p3, p4, p1)
                || d2 == 0 && onSegment(p3, p4, p2)
                || d3 == 0 && onSegment(p1, p2, p3)
                || d4 == 0 && onSegment(p1, p2, p4);
    }

    private static double cross(GeoPoint a, GeoPoint b, GeoPoint p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static boolean onSegment(GeoPoint a, GeoPoint b, GeoPoint p) {
        return Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.y(), b.y()) <= p.y() && p.y() <= Math.max(a.y(), b.y());
    }

    private static double distancePointToSegment(GeoPoint p, GeoPoint a, GeoPoint b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-12) {
            return p.distanceTo(a);
        }
        double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return p.distanceTo(new GeoPoint(a.x() + t * dx, a.y() + t * dy));
    }
}
