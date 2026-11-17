import type { CurrentPolicyDto, SwitchBodyDto, SwitchStatusDto } from '@/api/dto/backend';
import type { HttpClient } from '@/api/http/httpClient';
import { withRetry } from '@/api/http/retry';
import type { AdminPolicyGateway } from '@/api/ports';
import { ApiError, isApiError } from '@/domain/errors';
import { toActivePolicy, toSwitchId, toSwitchProgress } from './mappers';

export function createAdminPolicyAdapter(http: HttpClient): AdminPolicyGateway {
  return {
    async getActivePolicy(options) {
      const res = await withRetry(
        () => http.request<CurrentPolicyDto>({ method: 'GET', path: '/admin/policy', signal: options?.signal }),
        { signal: options?.signal },
      );
      if (!res.data) throw new ApiError({ kind: 'server', serverMessage: '정책 응답이 비어 있습니다.' });
      return toActivePolicy(res.data);
    },

    async requestSwitch(target, options) {
      const body: SwitchBodyDto = { target };
      try {
        // 전환 요청은 자동으로 다시 보내지 않는다. 응답을 못 받았다면 관리자가 현재 정책을 보고 판단한다.
        const res = await http.request<unknown>({
          method: 'POST',
          path: '/admin/policy/switch',
          body,
          signal: options?.signal,
        });
        const switchId = toSwitchId(res.data);
        if (!switchId) throw new ApiError({ kind: 'server', serverMessage: '전환 번호를 받지 못했습니다.' });
        return { switchId };
      } catch (e) {
        if (isApiError(e) && e.kind === 'conflict') throw e.as('switch_conflict');
        throw e;
      }
    },

    async getSwitchProgress(switchId, options) {
      const res = await http.request<SwitchStatusDto>({
        method: 'GET',
        path: `/admin/policy/switch/${encodeURIComponent(switchId)}`,
        signal: options?.signal,
      });
      if (!res.data) throw new ApiError({ kind: 'server', serverMessage: '전환 상태 응답이 비어 있습니다.' });
      return toSwitchProgress(res.data);
    },
  };
}
