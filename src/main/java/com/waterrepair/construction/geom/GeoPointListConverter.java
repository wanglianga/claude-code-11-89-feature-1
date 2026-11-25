package com.waterrepair.construction.geom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/** 折线段/多边形顶点在数据库中以 JSON 数组形式存储，业务侧使用 List&lt;GeoPoint&gt;。 */
@Converter
public class GeoPointListConverter implements AttributeConverter<List<GeoPoint>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<GeoPoint>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<GeoPoint> attribute) {
        if (attribute == null) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception ex) {
            throw new IllegalStateException("几何点列表序列化失败", ex);
        }
    }

    @Override
    public List<GeoPoint> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("几何点列表反序列化失败: " + dbData, ex);
        }
    }
}
