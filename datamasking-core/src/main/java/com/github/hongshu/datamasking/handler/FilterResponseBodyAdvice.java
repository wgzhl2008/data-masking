package com.github.hongshu.datamasking.handler;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.github.hongshu.datamasking.annotation.DataMasking;
import com.github.hongshu.datamasking.configuration.DataMaskingProperties;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class FilterResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Autowired
    private DataMaskingProperties dataMaskingProperties;
    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> IGNORE_PATH = Arrays.asList("/v2/api-docs", "/swagger-resources");

    @Override
    public boolean supports(@Nullable MethodParameter returnType, @Nullable Class<? extends HttpMessageConverter<?>> converterType) {
        return dataMaskingProperties.getEnable();
    }

    @Override
    public Object beforeBodyWrite(Object body, @Nullable MethodParameter returnType, @Nullable MediaType selectedContentType,
                                  @Nullable Class<? extends HttpMessageConverter<?>> selectedConverterType, @Nullable ServerHttpRequest request, @Nullable ServerHttpResponse response) {
        ServletServerHttpRequest req = (ServletServerHttpRequest) request;
        HttpServletRequest servletRequest = req.getServletRequest();
        String path = servletRequest.getServletPath();
        /*swagger文档不显示的问题*/
        if (IGNORE_PATH.contains(path)) {
            return body;
        }
        body = convertR(body, null);
        /*是否把body放入HandlerInterceptor response 中 默认，这时不设置，是没有的*/
       /* ServletServerHttpRequest req = (ServletServerHttpRequest) request;
        HttpServletRequest servletRequest = req.getServletRequest();
        servletRequest.setAttribute("response", body);*/
        return body;
    }

    @SuppressWarnings("rawtypes")
    private List<BeanPropertyDefinition> getBeanDesc(Class clazz) {
        JavaType javaType = objectMapper.constructType(clazz);
        SerializationConfig config = objectMapper.getSerializationConfig();
        BeanDescription beanDesc = config.introspect(javaType);
        return beanDesc.findProperties();
    }

    /**
     * 处理结果脱敏
     *
     * @param body        业务返回的结果
     * @param dataMasking 脱敏策略
     * @return
     */
    @SneakyThrows
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object convertR(Object body, DataMasking dataMasking) {
        if (ObjectUtils.isEmpty(body)) {
            return body;
        }
        /*如果结果是Map,需要做特殊处理*/
        if (body instanceof Map) {
            return postMapValue((Map) body, dataMasking);
        } else if (body instanceof List) {
            return convertRList((List) body, dataMasking);
        }
        Class clazz = body.getClass();
        List<BeanPropertyDefinition> beanDescList = getBeanDesc(clazz);
        Annotation clazzAnnotation = clazz.getAnnotation(DataMasking.class);
        DataMasking clazzDataMasking = dataMasking;
        if (ObjectUtils.isNotEmpty(clazzAnnotation)) {
            clazzDataMasking = (DataMasking) clazzAnnotation;
        }
        JSONObject meta = new JSONObject();
        for (BeanPropertyDefinition beanDesc : beanDescList) {
            dataMasking = getDataMasking(beanDesc, clazzDataMasking);
            postFieldValue(body, dataMasking, meta, beanDesc);
        }

        return meta;
    }

    /**
     * 获取脱敏注解，优先从最小单元获取，如果获取不到，从class上获取
     * 优先使用filed上的注解标识，其次是getter方法上的，解决了单一通过类反射获取
     * filed属性和固定getter方法的问题（通过利用JACKSON 的objectMapper机制）
     *
     * @param beanDesc   对象属性，包含保有get的方法
     * @param defaultVal 默认的脱敏注解从class上获取的
     * @return 获取脱敏注解
     */
    private DataMasking getDataMasking(BeanPropertyDefinition beanDesc, DataMasking defaultVal) {
        DataMasking dataMasking;
        if (ObjectUtils.isNotEmpty(beanDesc.getField())) {
            dataMasking = beanDesc.getField().getAnnotation(DataMasking.class);
            if (ObjectUtils.isEmpty(defaultVal)) {
                defaultVal = beanDesc.getField().getDeclaringClass().getAnnotation(DataMasking.class);
                /*当设置为false时，父类下属性不脱敏*/
                if (ObjectUtils.isNotEmpty(defaultVal) && !defaultVal.inherit()) {
                    defaultVal = null;
                }
            }
        } else {
            /*从getter方法上获取*/
            dataMasking = beanDesc.getGetter().getAnnotation(DataMasking.class);
            if (ObjectUtils.isEmpty(defaultVal)) {
                defaultVal = beanDesc.getGetter().getDeclaringClass().getAnnotation(DataMasking.class);
                /*当设置为false时，父类下属性不脱敏*/
                if (ObjectUtils.isNotEmpty(defaultVal) && !defaultVal.inherit()) {
                    defaultVal = null;
                }
            }
        }
        if (ObjectUtils.isEmpty(dataMasking)) {
            dataMasking = defaultVal;
        }
        return dataMasking;
    }

    /**
     * 处理返回传下为map（包含jsonObject）的场景
     *
     * @param body        会值
     * @param dataMasking 注释
     * @return
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private JSONObject postMapValue(Map body, final DataMasking dataMasking) {
        if (ObjectUtils.isEmpty(dataMasking)) {
            return new JSONObject(body);
        }
        JSONObject rJo = new JSONObject();
        /*
         *  如果是JsonObject,value可能会是各种类型，仍需要判断处理
         * */
        body.forEach((k, v) -> {
            if (v instanceof List) {
                /*list时，直接转list的处理方式，主要是JsonArray*/
                rJo.put(String.valueOf(k), convertRList((List) v, dataMasking));
            } else if (v instanceof Map || !isBaseObjType(v)) {
                /*map时，相当于实体对象，与非基本封装类一样，从新走对象处理逻辑*/
                rJo.put(String.valueOf(k), convertR(v, dataMasking));
            } else {
                if (ObjectUtils.isNotEmpty(v) && dataMasking.enabled()) {
                    /*不为空，且注释脱敏开关可用时，进行数据脱敏*/
                    rJo.put(String.valueOf(k), replaceSchema(String.valueOf(v), dataMasking));
                } else {
                    rJo.put(String.valueOf(k), v);
                }
            }

        });
        return rJo;
    }

    /**
     * 脱敏核心方法，从类的property的getter中获取值
     *
     * @param body        调用返回要过滤的对象
     * @param dataMasking 脱敏标识
     * @param meta        脱敏后数据存储器
     * @param beanDesc    处理对象中定义的属性信息，会综合从filed,getter中获取
     */
    @SuppressWarnings("rawtypes")
    @SneakyThrows
    private void postFieldValue(Object body, DataMasking dataMasking, JSONObject meta, BeanPropertyDefinition beanDesc) {
        Object fieldValue = beanDesc.getGetter().getValue(body);
        if (fieldValue instanceof List) {
            /*value为list或JsonArray时，的处理*/
            List<Object> fieldR = convertRList((List) fieldValue, dataMasking);
            meta.put(beanDesc.getName(), fieldR);
        } else if (fieldValue instanceof Map || !isBaseObjType(fieldValue)) {
            /*
             * map或JsonObject或自定义封装对象时处理，
             * convertR方法中会重新判断map或JsonObject的处理
             * */
            meta.put(beanDesc.getName(), convertR(fieldValue, dataMasking));
        } else {
            /*
             *  日期类型的处理，目前只处理 Date,LocalDateTime两种日期类型，当为这种日期类型时
             * 根据是否有JsonFormat的注解信息，进行日期格式化处理，
             * */
            if (isDateObj(fieldValue) && ObjectUtils.isNotEmpty(fieldValue)) {
                JsonFormat jsonFormat = ObjectUtils.isNotEmpty(beanDesc.getField()) ?
                        beanDesc.getField().getAnnotation(JsonFormat.class) : beanDesc.getGetter().getAnnotation(JsonFormat.class);
                String formatStr = ObjectUtils.isNotEmpty(jsonFormat) && StringUtils.isNotBlank(jsonFormat.pattern()) ? jsonFormat.pattern() : DatePattern.NORM_DATETIME_PATTERN;
                if (fieldValue instanceof Date) {
                    fieldValue = DateUtil.format((Date) fieldValue, formatStr);
                } else {
                    fieldValue = DateUtil.format((LocalDateTime) fieldValue, formatStr);
                }
            }
            /*数据脱敏化处理*/
            if (ObjectUtils.isNotEmpty(fieldValue) && ObjectUtils.isNotEmpty(dataMasking) && dataMasking.enabled()) {
                meta.put(beanDesc.getName(), replaceSchema(String.valueOf(fieldValue), dataMasking));
            } else {
                meta.put(beanDesc.getName(), fieldValue);
            }
        }
    }


    /**
     * 处理LIST的场景
     */
    @SneakyThrows
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Object> convertRList(List data, DataMasking dataMasking) {
        List<Object> convertR = new ArrayList<>(data.size());
        data.forEach(datum -> {
            if (isBaseObjType(datum)) {
                if (ObjectUtils.isNotEmpty(dataMasking) && dataMasking.enabled()) {
                    convertR.add(replaceSchema(String.valueOf(datum), dataMasking));
                } else {
                    convertR.add(datum);
                }
            } else {
                Object meta = convertR(datum, dataMasking);
                convertR.add(meta);
            }
        });

        return convertR;
    }

    /**
     * 被替换的字符
     * 字符串长度为1时，等量替换，如： abcedfg -> a******
     * 长度大于1时，非等量替换字字符串，如： masking=**  结果 abcedfg -> a**
     */
    private String replaceSchema(String str, DataMasking dataMasking) {
        if (dataMasking.strategy() != DesensitizedUtil.DesensitizedType.FIRST_MASK) {
            return DesensitizedUtil.desensitized(str, dataMasking.strategy());
        }
        if (StrUtil.length(dataMasking.masking()) > 1) {
            return StrUtil.replace(str, dataMasking.startInclude(),
                    dataMasking.endExclude(), dataMasking.masking());
        }
        return StrUtil.replace(str, dataMasking.startInclude(),
                dataMasking.endExclude(), dataMasking.masking().charAt(0));
    }

    /**
     * 判断是否是基本的数据类型
     */
    private boolean isBaseObjType(Object obj) {
        return obj instanceof String
                || obj instanceof Integer
                || obj instanceof Long
                || obj instanceof Double
                || obj instanceof Float
                || obj instanceof BigDecimal
                || obj instanceof Character
                || obj instanceof Boolean
                || obj instanceof Enum
                || obj instanceof Short
                || obj instanceof Byte
                || obj instanceof Date
                ;
    }

    private boolean isDateObj(Object obj) {
        return obj instanceof LocalDateTime
                || obj instanceof Date
                ;
    }


}
