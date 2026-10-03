const REQUIRED_STRING_FIELDS = [
  "id",
  "productName",
  "displayNameZhCN",
  "engineName",
  "taglineZhCN",
  "heroZhCN",
  "missionZhCN"
];

export function assertBrandConfig(config) {
  if (!config || typeof config !== "object" || Array.isArray(config)) {
    throw new TypeError("BrandConfig must be an object");
  }
  if (config.schema !== "org.foe.brand-config/v1") {
    throw new TypeError("unsupported BrandConfig schema");
  }
  for (const key of REQUIRED_STRING_FIELDS) {
    if (typeof config[key] !== "string" || config[key].trim() === "") {
      throw new TypeError(`BrandConfig.${key} is required`);
    }
  }
  if (config.features?.dailyStreak === true || config.features?.missedOpportunityCount === true) {
    throw new Error("BrandConfig cannot enable engagement pressure forbidden by FOE Core");
  }
  return config;
}
