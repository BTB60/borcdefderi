import { ApiError } from "@/lib/api";

/**
 * Müştəri borc limiti: runs a debt-writing call; when the server says CREDIT_LIMIT and the user is the owner, asks
 * whether to write it anyway and repeats the call with allowOverLimit. Everyone else just gets the error.
 */
export async function withCreditLimit<T>(isOwner: boolean, call: (allowOverLimit: boolean) => Promise<T>): Promise<T> {
  try {
    return await call(false);
  } catch (e) {
    if (e instanceof ApiError && e.code === "CREDIT_LIMIT" && isOwner
      && window.confirm(`${e.message}.\n\nLimiti keçərək yenə də borc yazılsın?`)) {
      return call(true);
    }
    throw e;
  }
}
