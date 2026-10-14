<template>
  <div class="apply">
    <a-card class="card" :bordered="false">
      <div class="eyebrow">CREDIT APPLICATION</div>
      <h1>申请企业账期</h1>
      <p class="sub">根据采购规模填写申请额度与账期，审核通过后即可在结算页使用。</p>
      <a-alert type="info" show-icon message="申请口径" description="平台会综合企业资料、采购记录与履约情况评估授信；页面额度为申请上限，并非承诺授信额度。" class="alert"/>
      <a-form layout="vertical" :model="form" @finish="submit">
        <a-form-item label="申请额度" :rules="[{required:true,message:'请输入申请额度'}]">
          <a-input-number v-model:value="form.requestedLimit" :min="1000" :max="500000" :precision="2" style="width:100%" addon-before="¥"/>
        </a-form-item>
        <a-form-item label="账期">
          <a-radio-group v-model:value="form.termDays" class="terms">
            <a-radio-button :value="30">30 天</a-radio-button>
            <a-radio-button :value="60">60 天</a-radio-button>
            <a-radio-button :value="90">90 天</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="主要用途">
          <a-textarea v-model:value="form.purpose" :rows="4" maxlength="255" show-count placeholder="例如：钢材原料采购、季节性备货、项目型集中采购"/>
        </a-form-item>
        <div class="actions"><a-button @click="$router.push('/credit-term')">返回</a-button><a-button type="primary" html-type="submit" :loading="loading">提交申请</a-button></div>
      </a-form>
    </a-card>
  </div>
</template>
<script setup>
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { creditTermApi } from '../api'
const loading=ref(false),form=reactive({requestedLimit:100000,termDays:30,purpose:''})
async function submit(){loading.value=true;try{await creditTermApi.apply(form);message.success('账期申请已提交');location.href='/credit-term'}finally{loading.value=false}}
</script>
<style scoped>
.apply{max-width:720px;margin:0 auto}.card{border-radius:22px;box-shadow:0 18px 60px rgba(19,35,58,.08)}.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}h1{margin:6px 0}.sub{color:var(--ll-muted);margin-bottom:18px}.alert{margin-bottom:20px;border-radius:12px}.terms{display:flex}.actions{display:flex;justify-content:flex-end;gap:10px}
</style>
