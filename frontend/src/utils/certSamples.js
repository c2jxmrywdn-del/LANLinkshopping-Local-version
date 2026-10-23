/**
 * 入驻证照示例照片与文案。
 *
 * 三类材料（营业执照 / 完税凭证 / 特殊经营许可证）各配一张示例照片：
 *  - 用于上传区的填写引导，告诉申请人「合格的原件长什么样」；
 *  - 作为图片加载失败时的兜底说明来源（见 CertImage.vue）。
 *
 * tax-proof.jpg / tax-proof-full.jpg：取自政府公开采购文件中的真实《税收完税证明》扫描件
 * （企业所得税，税款所属期 2025-08-01 至 2025-08-31）；票证号、纳税人名称/识别号、
 * 实缴金额与合计金额均已打码。
 *
 * special-permit.jpg / special-permit-full.jpg：取自政府网站公开的《危险化学品经营许可证》
 * （副本）实拍照片（有效期 2024-06-17 至 2027-06-16）；统一社会信用代码、企业名称、
 * 企业住所、法定代表人、证书编号及二维码均已打码。
 *
 * 图片为本地静态资源（public/samples），离线可用，不依赖外链。
 * 如替换示例图，保持同名同格式即可，无需改代码。
 */
export const CERT_SAMPLES = {
  license: {
    src: '/samples/business-license.jpg',
    title: '营业执照示例照片',
    alt: '营业执照示例照片',
    desc: '红色边框、国徽、统一社会信用代码与发照机关公章需完整入镜，四角不裁切。'
  },
  tax: {
    src: '/samples/tax-proof.jpg',
    fullSrc: '/samples/tax-proof-full.jpg',
    title: '企业所得税完税凭证示例照片',
    alt: '企业所得税税收完税证明示例照片',
    desc: '真实《税收完税证明》：需含企业所得税税种、税款所属期、入（退）库日期与税务机关印章；票证号、纳税人识别号、金额已打码。'
  },
  permit: {
    src: '/samples/special-permit.jpg',
    fullSrc: '/samples/special-permit-full.jpg',
    title: '危险化学品经营许可证示例照片',
    alt: '《危险化学品经营许可证》示例照片',
    desc: '真实《危险化学品经营许可证》（副本）：需含许可证编号、经营方式、许可范围、有效期与发证机关印章；示例中编号、统一社会信用代码、企业名称、住所、法定代表人及二维码已打码。'
  }
}

/** 按证照类型取示例；未知类型回退到营业执照示例，避免渲染空块 */
export function certSampleOf(kind) {
  return CERT_SAMPLES[kind] || CERT_SAMPLES.license
}
