import { apiRequest } from './client';

export interface SellerPolicy {
  bargainAccepted: boolean;
  floorPrice: number | string | null;
  allowedDiscount: number | string | null;
  freeShippingCondition: string;
  shippingArrangement: string;
}
export interface ItemProfile {
  title: string;
  productCategory: string;
  productModel: string;
  description: string;
  condition: string;
  accessories: string;
  sellerPolicy: SellerPolicy;
}
export interface ItemRevision {
  itemId: string;
  version: number;
  draft: ItemProfile;
  published: ItemProfile | null;
  updatedAt: number;
  reviewedBy: string;
}
export interface ItemView {
  itemId: string;
  facts: Omit<ItemProfile, 'sellerPolicy'> & { price: { min: number | null; max: number | null }; stock: number } | null;
  profile: ItemRevision | null;
}
export interface ManualView { conversationId: string; manualMode: boolean }

const base = '/api/admin/xianyu';
export const xianyuApi = {
  items: (token: string, signal?: AbortSignal) => apiRequest<ItemView[]>(`${base}/items`, { token, signal }),
  saveDraft: (token: string, itemId: string, expectedVersion: number, profile: ItemProfile, signal?: AbortSignal) =>
    apiRequest<ItemRevision>(`${base}/items/${encodeURIComponent(itemId)}/draft`, { method: 'PUT', token, signal, body: { expectedVersion, profile } }),
  publish: (token: string, itemId: string, expectedVersion: number, signal?: AbortSignal) =>
    apiRequest<ItemRevision>(`${base}/items/${encodeURIComponent(itemId)}/publish`, { method: 'POST', token, signal, body: { expectedVersion } }),
  manualMode: (token: string, buyerId: string, itemId: string, signal?: AbortSignal) =>
    apiRequest<ManualView>(`${base}/conversations/manual-mode?${new URLSearchParams({ buyerId, itemId })}`, { token, signal }),
  setManualMode: (token: string, buyerId: string, itemId: string, enabled: boolean, signal?: AbortSignal) =>
    apiRequest<ManualView>(`${base}/conversations/manual-mode`, { method: 'POST', token, signal, body: { buyerId, itemId, enabled } }),
};
