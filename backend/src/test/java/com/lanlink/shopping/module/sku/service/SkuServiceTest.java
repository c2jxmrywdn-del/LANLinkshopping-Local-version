package com.lanlink.shopping.module.sku.service;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.sku.dto.SkuTreeSaveRequest;
import com.lanlink.shopping.module.sku.entity.*;
import com.lanlink.shopping.module.sku.mapper.*;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.math.BigDecimal; import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkuServiceTest {
    private ProductSpecMapper specMapper; private ProductSpecValueMapper valueMapper;
    private ProductSkuMapper skuMapper; private ProductMapper productMapper;
    private TechComplianceGuard guard; private SkuService service;

    @BeforeAll static void initTableInfo(){
        var assistant = new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProductSpec.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProductSpecValue.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProductSku.class);
    }
    @BeforeEach void setup(){
        specMapper=mock(ProductSpecMapper.class); valueMapper=mock(ProductSpecValueMapper.class);
        skuMapper=mock(ProductSkuMapper.class); productMapper=mock(ProductMapper.class);
        guard=mock(TechComplianceGuard.class);
        service=new SkuService(specMapper,valueMapper,skuMapper,productMapper,guard);
        Product p=new Product(); p.setProdId(100L); p.setMerId(7L); p.setDeleted(0);
        when(productMapper.selectById(100L)).thenReturn(p);
    }
    private SkuTreeSaveRequest tree2x2(){
        SkuTreeSaveRequest r=new SkuTreeSaveRequest();
        var s1=new SkuTreeSaveRequest.SpecIn(); s1.setName("颜色"); s1.setValues(List.of("红","蓝"));
        var s2=new SkuTreeSaveRequest.SpecIn(); s2.setName("尺寸"); s2.setValues(List.of("S","L"));
        r.setSpecs(List.of(s1,s2));
        var skus=new ArrayList<SkuTreeSaveRequest.SkuIn>();
        for(int a=0;a<2;a++) for(int b=0;b<2;b++){
            var si=new SkuTreeSaveRequest.SkuIn();
            var v1=new SkuTreeSaveRequest.ValueRef(); v1.setSpecIndex(0); v1.setValueIndex(a);
            var v2=new SkuTreeSaveRequest.ValueRef(); v2.setSpecIndex(1); v2.setValueIndex(b);
            si.setValues(List.of(v1,v2)); si.setPrice(new BigDecimal("9.90")); si.setStock(10);
            skus.add(si);
        }
        r.setSkus(skus); return r;
    }
    @Test void save_rejects_not_owned(){ Product p=new Product(); p.setProdId(100L); p.setMerId(999L); p.setDeleted(0);
        when(productMapper.selectById(100L)).thenReturn(p);
        BusinessException e=assertThrows(BusinessException.class,()->service.save(7L,100L,tree2x2()));
        assertEquals(403,e.getCode()); verify(skuMapper,never()).insert(any(ProductSku.class)); }
    @Test void save_rejects_missing_product(){ when(productMapper.selectById(100L)).thenReturn(null);
        BusinessException e=assertThrows(BusinessException.class,()->service.save(7L,100L,tree2x2()));
        assertEquals(404,e.getCode()); }
    @Test void save_rejects_too_many_dims(){ SkuTreeSaveRequest r=tree2x2();
        var s3=new SkuTreeSaveRequest.SpecIn(); s3.setName("材质"); s3.setValues(List.of("钢"));
        r.setSpecs(List.of(r.getSpecs().get(0),r.getSpecs().get(1),s3, new SkuTreeSaveRequest.SpecIn(){{setName("x");setValues(List.of("y"));}}));
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); verify(skuMapper,never()).insert(any(ProductSku.class)); }
    @Test void save_rejects_duplicate_combo(){ SkuTreeSaveRequest r=tree2x2(); r.getSkus().add(r.getSkus().get(0));
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); verify(skuMapper,never()).insert(any(ProductSku.class)); }
    @Test void save_rejects_sku_missing_dim(){ SkuTreeSaveRequest r=tree2x2(); r.getSkus().get(0).setValues(List.of(r.getSkus().get(0).getValues().get(0)));
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); }
    @Test void save_rejects_zero_price(){ SkuTreeSaveRequest r=tree2x2(); r.getSkus().get(0).setPrice(BigDecimal.ZERO);
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); }
    @Test void save_rejects_negative_stock(){ SkuTreeSaveRequest r=tree2x2(); r.getSkus().get(0).setStock(-1);
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); }
    @Test void save_rejects_compliance(){ SkuTreeSaveRequest r=tree2x2();
        doThrow(new BusinessException(400,"合规")).when(guard).validate(eq("specName"),anyString());
        assertThrows(BusinessException.class,()->service.save(7L,100L,r)); }
    @Test void save_empty_tree_deletes_all(){ SkuTreeSaveRequest r=new SkuTreeSaveRequest(); r.setSpecs(List.of()); r.setSkus(List.of());
        service.save(7L,100L,r);
        verify(skuMapper).delete(any()); verify(valueMapper).delete(any()); verify(specMapper).delete(any());
        verify(skuMapper,never()).insert(any(ProductSku.class)); }
    @Test void save_valid_inserts_specs_values_skus(){
        // insert 回填 id：spec→10/11，value→20/21/22/23
        var specIds=new long[]{10L}; var valueIds=new long[]{20L};
        doAnswer(inv->{ ((ProductSpec)inv.getArgument(0)).setSpecId(specIds[0]++); return 1;}).when(specMapper).insert(any(ProductSpec.class));
        doAnswer(inv->{ ((ProductSpecValue)inv.getArgument(0)).setValueId(valueIds[0]++); return 1;}).when(valueMapper).insert(any(ProductSpecValue.class));
        when(specMapper.selectList(any())).thenReturn(List.of()); when(valueMapper.selectList(any())).thenReturn(List.of()); when(skuMapper.selectList(any())).thenReturn(List.of());
        service.save(7L,100L,tree2x2());
        verify(specMapper,times(2)).insert(any(ProductSpec.class));
        verify(valueMapper,times(4)).insert(any(ProductSpecValue.class));
        var cap=org.mockito.ArgumentCaptor.forClass(ProductSku.class);
        verify(skuMapper,times(4)).insert(cap.capture());
        var combos=cap.getAllValues().stream().map(ProductSku::getSpecValueIds).toList();
        assertEquals(4,new HashSet<>(combos).size()); // 组合唯一
        combos.forEach(c->{
            assertTrue(c.matches("\\d+,\\d+"), "comma format: "+c);
            String[] p=c.split(",");
            assertTrue(Long.parseLong(p[0])<Long.parseLong(p[1]), "ascending ids: "+c);
        }); // 升序逗号串
    }

    /** 回填 id 并跑一次 save，返回 4 行 ProductSku 的捕获结果。 */
    private List<ProductSku> saveAndCaptureSkus(SkuTreeSaveRequest r){
        var specIds=new long[]{10L}; var valueIds=new long[]{20L};
        doAnswer(inv->{ ((ProductSpec)inv.getArgument(0)).setSpecId(specIds[0]++); return 1;}).when(specMapper).insert(any(ProductSpec.class));
        doAnswer(inv->{ ((ProductSpecValue)inv.getArgument(0)).setValueId(valueIds[0]++); return 1;}).when(valueMapper).insert(any(ProductSpecValue.class));
        when(specMapper.selectList(any())).thenReturn(List.of()); when(valueMapper.selectList(any())).thenReturn(List.of()); when(skuMapper.selectList(any())).thenReturn(List.of());
        service.save(7L,100L,r);
        var cap=org.mockito.ArgumentCaptor.forClass(ProductSku.class);
        verify(skuMapper,times(4)).insert(cap.capture());
        return cap.getAllValues();
    }

    @Test void save_sku_code_persisted_trimmed(){
        SkuTreeSaveRequest r=tree2x2();
        List<String> codes=List.of(" SKU-R-S ","SKU-R-L","SKU-B-S","SKU-B-L");
        for(int i=0;i<4;i++) r.getSkus().get(i).setSkuCode(codes.get(i));
        var rows=saveAndCaptureSkus(r);
        assertEquals(List.of("SKU-R-S","SKU-R-L","SKU-B-S","SKU-B-L"),
                rows.stream().map(ProductSku::getSkuCode).toList()); // 首尾空白被 trim
        assertEquals(4,new HashSet<>(rows.stream().map(ProductSku::getSkuCode).toList()).size());
    }

    @Test void save_blank_sku_code_normalized_to_null(){
        SkuTreeSaveRequest r=tree2x2();
        r.getSkus().get(0).setSkuCode("   ");   // 全空白 → null（uk_sku_code 允许多个 NULL）
        r.getSkus().get(1).setSkuCode("");
        r.getSkus().get(2).setSkuCode(null);
        r.getSkus().get(3).setSkuCode("SKU-4");
        var rows=saveAndCaptureSkus(r);
        assertNull(rows.get(0).getSkuCode());
        assertNull(rows.get(1).getSkuCode());
        assertNull(rows.get(2).getSkuCode());
        assertEquals("SKU-4",rows.get(3).getSkuCode());
    }

    @Test void save_rejects_duplicate_sku_code(){
        SkuTreeSaveRequest r=tree2x2();
        r.getSkus().get(0).setSkuCode("DUP-1");
        r.getSkus().get(1).setSkuCode(" DUP-1 ");  // trim 后同码
        BusinessException e=assertThrows(BusinessException.class,()->service.save(7L,100L,r));
        assertEquals(400,e.getCode());
        assertTrue(e.getMessage().contains("SKU 编码不可重复"), e.getMessage());
        verify(skuMapper,never()).insert(any(ProductSku.class));
    }

    @Test void save_rejects_overlong_sku_code(){
        SkuTreeSaveRequest r=tree2x2();
        r.getSkus().get(0).setSkuCode("X".repeat(65));
        BusinessException e=assertThrows(BusinessException.class,()->service.save(7L,100L,r));
        assertEquals(400,e.getCode());
        verify(skuMapper,never()).insert(any(ProductSku.class));
    }
}