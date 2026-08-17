import { api } from "./api";
import {
  serializeDeliveryQuery,
  type DeliveryFailurePage,
  type DeliveryOverview,
  type DeliveryQuery,
  type DeliveryTaskPage,
  type DeliveryTimeseries
} from "./deliveryObservabilityQuery";

export * from "./deliveryObservabilityQuery";

export function getDeliveryOverview(query: DeliveryQuery = {}): Promise<DeliveryOverview> {
  return api.get<DeliveryOverview, DeliveryOverview>("/admin/observability/delivery/overview", {
    params: serializeDeliveryQuery(query)
  });
}

export function getDeliveryTimeseries(query: DeliveryQuery = {}): Promise<DeliveryTimeseries> {
  return api.get<DeliveryTimeseries, DeliveryTimeseries>("/admin/observability/delivery/timeseries", {
    params: serializeDeliveryQuery({ projectId: query.projectId, window: query.window })
  });
}

export function getDeliveryFailures(query: DeliveryQuery = {}): Promise<DeliveryFailurePage> {
  return api.get<DeliveryFailurePage, DeliveryFailurePage>("/admin/observability/delivery/failures", {
    params: serializeDeliveryQuery(query)
  });
}

export function getDeliveryTasks(query: DeliveryQuery = {}): Promise<DeliveryTaskPage> {
  return api.get<DeliveryTaskPage, DeliveryTaskPage>("/admin/observability/delivery/tasks", {
    params: serializeDeliveryQuery(query)
  });
}
