package com.lanlink.shopping.module.sku.service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.sku.dto.*;
import com.lanlink.shopping.module.sku.entity.*;
import com.lanlink.shopping.module.sku.mapper.*;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal; import java.time.LocalDateTime; import java.util.*;
import java.util.stream.Collectors;

@Service
public class SkuService {
    private static final BigDecimal MAX_PRICE=new BigDecimal("999999999999.99");
    private final ProductSpecMapper specMapper; private final ProductSpecValueMapper valueMapper;
    private final ProductSkuMapper skuMapper; private final ProductMapper productMapper;
    private final TechComplianceGuard complianceGuard;
    public SkuService(ProductSpecMapper s,ProductSpecValueMapper v,ProductSkuMapper k,ProductMapper p,TechComplianceGuard g){
        specMapper=s; valueMapper=v; skuMapper=k; productMapper=p; complianceGuard=g;
    }
    public SkuTreeView load(Long merId,Long prodId){ requireOwned(merId,prodId); return buildView(prodId); }

    @Transactional
    public SkuTreeView save(Long merId,Long prodId,SkuTreeSaveRequest req){
        requireOwned(merId,prodId);
        List<SkuTreeSaveRequest.SpecIn> specs=req.getSpecs()==null?List.of():req.getSpecs();
        List<SkuTreeSaveRequest.SkuIn> skus=req.getSkus()==null?List.of():req.getSkus();
        validate(specs,skus);
        skuMapper.delete(Wrappers.<ProductSku>lambdaQuery().eq(ProductSku::getProdId,prodId));
        valueMapper.delete(Wrappers.<ProductSpecValue>lambdaQuery().eq(ProductSpecValue::getProdId,prodId));
        specMapper.delete(Wrappers.<ProductSpec>lambdaQuery().eq(ProductSpec::getProdId,prodId));
        if(specs.isEmpty()) return buildView(prodId);
        List<List<Long>> valueIds=new ArrayList<>();
        for(int i=0;i<specs.size();i++){
            var s=specs.get(i);
            ProductSpec ps=new ProductSpec(); ps.setProdId(prodId); ps.setSpecName(s.getName().trim()); ps.setSortOrder(i); ps.setCreateTime(LocalDateTime.now());
            specMapper.insert(ps);
            List<Long> vids=new ArrayList<>();
            for(int j=0;j<s.getValues().size();j++){
                ProductSpecValue v=new ProductSpecValue(); v.setSpecId(ps.getSpecId()); v.setProdId(prodId);
                v.setSpecValue(s.getValues().get(j).trim()); v.setSortOrder(j); v.setCreateTime(LocalDateTime.now());
                valueMapper.insert(v); vids.add(v.getValueId());
            }
            valueIds.add(vids);
        }
        for(var si:skus){
            List<Long> combo=new ArrayList<>();
            for(var ref:si.getValues()) combo.add(valueIds.get(ref.getSpecIndex()).get(ref.getValueIndex()));
            Collections.sort(combo);
            ProductSku sku=new ProductSku(); sku.setProdId(prodId);
            sku.setSkuCode(si.getSkuCode()==null||si.getSkuCode().isBlank()?null:si.getSkuCode().trim());
            sku.setSpecValueIds(combo.stream().map(String::valueOf).collect(Collectors.joining(",")));
            sku.setPrice(si.getPrice()); sku.setStock(si.getStock()); sku.setStatus(1);
            sku.setCreateTime(LocalDateTime.now()); sku.setUpdateTime(LocalDateTime.now());
            skuMapper.insert(sku);
        }
        return buildView(prodId);
    }

    private void requireOwned(Long merId,Long prodId){
        Product p=productMapper.selectById(prodId);
        if(p==null||(p.getDeleted()!=null&&p.getDeleted()==1)) throw new BusinessException(404,"商品不存在");
        if(!Objects.equals(p.getMerId(),merId)) throw new BusinessException(403,"只能管理自有商品");
    }

    private void validate(List<SkuTreeSaveRequest.SpecIn> specs,List<SkuTreeSaveRequest.SkuIn> skus){
        if(specs.size()>3) throw new BusinessException(400,"规格维度最多 3 个");
        Set<String> seenCombo=new HashSet<>(); Set<String> seenCode=new HashSet<>();
        for(var s:specs){
            if(s.getName()==null||s.getName().trim().isEmpty()||s.getName().trim().length()>20) throw new BusinessException(400,"规格名需 1-20 字符");
            complianceGuard.validate("specName",s.getName().trim());
            if(s.getValues()==null||s.getValues().isEmpty()||s.getValues().size()>30) throw new BusinessException(400,"每维规格值需 1-30 个");
            Set<String> vv=new HashSet<>();
            for(String val:s.getValues()){
                if(val==null||val.trim().isEmpty()||val.trim().length()>40) throw new BusinessException(400,"规格值需 1-40 字符");
                complianceGuard.validate("specValue",val.trim());
                if(!vv.add(val.trim())) throw new BusinessException(400,"同维规格值不可重复");
            }
        }
        if(!specs.isEmpty()&&skus.size()>200) throw new BusinessException(400,"SKU 最多 200 个");
        for(var si:skus){
            if(si.getValues()==null||si.getValues().size()!=specs.size()) throw new BusinessException(400,"每个 SKU 须恰好每维一个值");
            boolean[] covered=new boolean[specs.size()]; List<Integer> idxs=new ArrayList<>();
            for(var ref:si.getValues()){
                int sp=ref.getSpecIndex(),va=ref.getValueIndex();
                if(sp<0||sp>=specs.size()||va<0||va>=specs.get(sp).getValues().size()) throw new BusinessException(400,"规格值引用越界");
                if(covered[sp]) throw new BusinessException(400,"同一维度不可选多个值");
                covered[sp]=true; idxs.add(sp*1000+va);
            }
            for(boolean c:covered) if(!c) throw new BusinessException(400,"每个 SKU 须恰好每维一个值");
            Collections.sort(idxs);
            if(!seenCombo.add(idxs.toString())) throw new BusinessException(400,"SKU 组合不可重复");
            if(si.getPrice()==null||si.getPrice().compareTo(BigDecimal.ZERO)<=0||si.getPrice().compareTo(MAX_PRICE)>0||si.getPrice().scale()>2) throw new BusinessException(400,"价格需 0.01-999999999999.99 且最多两位小数");
            if(si.getStock()==null||si.getStock()<0) throw new BusinessException(400,"库存需为非负整数");
            if(si.getSkuCode()!=null&&!si.getSkuCode().isBlank()){
                if(si.getSkuCode().trim().length()>64) throw new BusinessException(400,"SKU 编码最多 64 字符");
                if(!seenCode.add(si.getSkuCode().trim())) throw new BusinessException(400,"SKU 编码不可重复");
            }
        }
    }

    private SkuTreeView buildView(Long prodId){
        var specs=specMapper.selectList(Wrappers.<ProductSpec>lambdaQuery().eq(ProductSpec::getProdId,prodId).orderByAsc(ProductSpec::getSortOrder));
        var values=valueMapper.selectList(Wrappers.<ProductSpecValue>lambdaQuery().eq(ProductSpecValue::getProdId,prodId).orderByAsc(ProductSpecValue::getSortOrder));
        var skus=skuMapper.selectList(Wrappers.<ProductSku>lambdaQuery().eq(ProductSku::getProdId,prodId).orderByAsc(ProductSku::getSkuId));
        Map<Long,String> valueText=new HashMap<>(); Map<Long,String> valueSpecName=new HashMap<>();
        Map<Long,String> specName=new HashMap<>();
        for(var s:specs) specName.put(s.getSpecId(),s.getSpecName());
        for(var v:values){ valueText.put(v.getValueId(),v.getSpecValue()); valueSpecName.put(v.getValueId(),specName.getOrDefault(v.getSpecId(),"")); }
        SkuTreeView view=new SkuTreeView();
        List<SkuTreeView.SpecOut> so=new ArrayList<>();
        for(var s:specs){ var o=new SkuTreeView.SpecOut(); o.setSpecId(s.getSpecId()); o.setName(s.getSpecName());
            List<SkuTreeView.ValueOut> vo=new ArrayList<>();
            for(var v:values) if(Objects.equals(v.getSpecId(),s.getSpecId())){ var x=new SkuTreeView.ValueOut(); x.setValueId(v.getValueId()); x.setValue(v.getSpecValue()); vo.add(x); }
            o.setValues(vo); so.add(o); }
        view.setSpecs(so);
        List<SkuTreeView.SkuOut> ko=new ArrayList<>();
        for(var k:skus){ var o=new SkuTreeView.SkuOut(); o.setSkuId(k.getSkuId()); o.setSkuCode(k.getSkuCode()); o.setSpecValueIds(k.getSpecValueIds());
            o.setPrice(k.getPrice()); o.setStock(k.getStock()); o.setStatus(k.getStatus());
            String text=Arrays.stream(k.getSpecValueIds().split(",")).map(Long::valueOf)
                .map(id->valueSpecName.getOrDefault(id,"")+":"+valueText.getOrDefault(id,"?")).collect(Collectors.joining(";"));
            o.setSpecText(text); ko.add(o); }
        view.setSkus(ko); return view;
    }
}