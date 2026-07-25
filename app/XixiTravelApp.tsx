"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import "maplibre-gl/dist/maplibre-gl.css";

type RideType = "轻享" | "舒适" | "六座";
type Stage = "quotes" | "booking" | "booked";
type AppView = "ride" | "trips" | "invoices";
type TripStatus =
  | "driver_arriving"
  | "driver_arrived"
  | "completed"
  | "cancelled";
type InvoiceStatus = "available" | "processing" | "issued" | "unavailable";

type TripRecord = {
  id: string;
  origin: string;
  destination: string;
  vehicle: RideType;
  price: number;
  status: TripStatus;
  startedAt: string;
  durationMinutes: number;
  distanceKilometers: number;
  invoiceStatus: InvoiceStatus;
  invoiceNumber?: string;
};

const STORAGE_KEY = "xixi-travel-trips-v2";

const rideOptions = [
  {
    name: "轻享" as RideType,
    description: "经济实惠 · 预计 3 分钟接驾",
    price: 42,
    seats: 4,
    eta: "3 分钟",
    etaMinutes: 3,
    badge: "省 ¥8",
  },
  {
    name: "舒适" as RideType,
    description: "宽敞安静 · 预计 5 分钟接驾",
    price: 58,
    seats: 4,
    eta: "5 分钟",
    etaMinutes: 5,
    badge: "推荐",
  },
  {
    name: "六座" as RideType,
    description: "多人同行 · 预计 7 分钟接驾",
    price: 76,
    seats: 6,
    eta: "7 分钟",
    etaMinutes: 7,
    badge: "空间大",
  },
];

const initialTrips: TripRecord[] = [
  {
    id: "XIXI-0721-A8F3",
    origin: "国贸中心",
    destination: "首都机场 T3",
    vehicle: "舒适",
    price: 86,
    status: "completed",
    startedAt: "2026-07-21T17:42:00+08:00",
    durationMinutes: 46,
    distanceKilometers: 28.4,
    invoiceStatus: "available",
  },
  {
    id: "XIXI-0718-C2D7",
    origin: "北京南站",
    destination: "中关村软件园",
    vehicle: "轻享",
    price: 64,
    status: "completed",
    startedAt: "2026-07-18T09:16:00+08:00",
    durationMinutes: 51,
    distanceKilometers: 24.8,
    invoiceStatus: "issued",
    invoiceNumber: "XIXI2607180036",
  },
  {
    id: "XIXI-0713-B9K1",
    origin: "三里屯太古里",
    destination: "故宫博物院",
    vehicle: "轻享",
    price: 35,
    status: "completed",
    startedAt: "2026-07-13T14:05:00+08:00",
    durationMinutes: 32,
    distanceKilometers: 9.7,
    invoiceStatus: "unavailable",
  },
];

const routeCoordinates: [number, number][] = [
  [116.3977, 39.9087],
  [116.393, 39.899],
  [116.389, 39.887],
  [116.386, 39.873],
  [116.3846, 39.8652],
];

const clockFormatter = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

const shortTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const tripDateFormatter = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

function statusLabel(status: TripStatus) {
  return {
    driver_arriving: "司机赶来中",
    driver_arrived: "司机已到达",
    completed: "已完成",
    cancelled: "已取消",
  }[status];
}

function invoiceLabel(status: InvoiceStatus) {
  return {
    available: "可开票",
    processing: "开票中",
    issued: "申请成功",
    unavailable: "不可开票",
  }[status];
}

function formatCountdown(totalSeconds: number) {
  const safeSeconds = Math.max(0, totalSeconds);
  const minutes = Math.floor(safeSeconds / 60);
  const seconds = safeSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

export function XixiTravelApp() {
  const mapContainer = useRef<HTMLDivElement>(null);
  const [activeView, setActiveView] = useState<AppView>("ride");
  const [rideType, setRideType] = useState<RideType>("舒适");
  const [stage, setStage] = useState<Stage>("quotes");
  const [destination, setDestination] = useState("北京南站");
  const [message, setMessage] = useState("");
  const [assistantText, setAssistantText] = useState(
    "路线已规划好。现在出发约 28 分钟，我为你避开了前三门附近的拥堵路段。",
  );
  const [now, setNow] = useState<Date | null>(null);
  const [driverDueAt, setDriverDueAt] = useState<string | null>(null);
  const [trips, setTrips] = useState<TripRecord[]>(initialTrips);
  const [hasHydrated, setHasHydrated] = useState(false);
  const [selectedInvoiceTripId, setSelectedInvoiceTripId] = useState<
    string | null
  >(null);
  const [invoiceTitle, setInvoiceTitle] = useState("个人");
  const [invoiceEmail, setInvoiceEmail] = useState("");
  const [notice, setNotice] = useState("");

  const selectedRide =
    rideOptions.find((option) => option.name === rideType) ?? rideOptions[1];
  const activeTrip = trips.find(
    (trip) =>
      trip.status === "driver_arriving" || trip.status === "driver_arrived",
  );
  const completedTrips = trips.filter((trip) => trip.status === "completed");
  const invoiceTrips = completedTrips.filter(
    (trip) => trip.invoiceStatus !== "unavailable",
  );

  const currentClock = now ? clockFormatter.format(now) : "--:--:--";
  const arrivalTime = now
    ? shortTimeFormatter.format(
        new Date(now.getTime() + 28 * 60 * 1000),
      )
    : "--:--";
  const driverCountdown =
    now && driverDueAt
      ? Math.max(
          0,
          Math.ceil(
            (new Date(driverDueAt).getTime() - now.getTime()) / 1000,
          ),
        )
      : selectedRide.etaMinutes * 60;
  const greeting = (() => {
    if (!now) return "你好";
    const hour = Number(
      new Intl.DateTimeFormat("en-US", {
        timeZone: "Asia/Shanghai",
        hour: "2-digit",
        hour12: false,
      }).format(now),
    );
    if (hour < 6) return "夜深了";
    if (hour < 12) return "早上好";
    if (hour < 18) return "下午好";
    return "晚上好";
  })();

  useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    try {
      const savedTrips = window.localStorage.getItem(STORAGE_KEY);
      if (savedTrips) {
        const parsed = JSON.parse(savedTrips) as TripRecord[];
        if (Array.isArray(parsed)) {
          setTrips(parsed);
          const savedActiveTrip = parsed.find(
            (trip) =>
              trip.status === "driver_arriving" ||
              trip.status === "driver_arrived",
          );
          if (savedActiveTrip) {
            setStage("booked");
            const savedDueAt = window.localStorage.getItem(
              `${STORAGE_KEY}-driver-due`,
            );
            setDriverDueAt(
              savedDueAt ??
                new Date(Date.now() + 3 * 60 * 1000).toISOString(),
            );
          }
        }
      }
    } catch {
      setTrips(initialTrips);
    } finally {
      setHasHydrated(true);
    }
  }, []);

  useEffect(() => {
    if (!hasHydrated) return;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(trips));
    if (driverDueAt) {
      window.localStorage.setItem(
        `${STORAGE_KEY}-driver-due`,
        driverDueAt,
      );
    } else {
      window.localStorage.removeItem(`${STORAGE_KEY}-driver-due`);
    }
  }, [driverDueAt, hasHydrated, trips]);

  useEffect(() => {
    if (
      driverCountdown > 0 ||
      !activeTrip ||
      activeTrip.status !== "driver_arriving"
    ) {
      return;
    }
    setTrips((currentTrips) =>
      currentTrips.map((trip) =>
        trip.id === activeTrip.id
          ? { ...trip, status: "driver_arrived" }
          : trip,
      ),
    );
    setAssistantText("王师傅已到达上车点，请核对车牌后上车。");
  }, [activeTrip, driverCountdown]);

  useEffect(() => {
    if (activeView !== "ride" || !mapContainer.current) return;

    let map: import("maplibre-gl").Map | undefined;
    let cancelled = false;

    import("maplibre-gl").then(({ default: maplibregl }) => {
      if (cancelled || !mapContainer.current) return;

      map = new maplibregl.Map({
        container: mapContainer.current,
        center: [116.391, 39.887],
        zoom: 12.2,
        attributionControl: false,
        style: {
          version: 8,
          sources: {
            osm: {
              type: "raster",
              tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              tileSize: 256,
              attribution: "© OpenStreetMap contributors",
            },
          },
          layers: [{ id: "osm", type: "raster", source: "osm" }],
        },
      });

      map.addControl(
        new maplibregl.NavigationControl({ showCompass: false }),
        "bottom-right",
      );

      const start = document.createElement("div");
      start.className = "map-marker map-marker-start";
      start.setAttribute("aria-label", "出发地：故宫博物院");

      const end = document.createElement("div");
      end.className = "map-marker map-marker-end";
      end.setAttribute("aria-label", `目的地：${destination}`);

      new maplibregl.Marker({ element: start })
        .setLngLat(routeCoordinates[0])
        .addTo(map);
      new maplibregl.Marker({ element: end })
        .setLngLat(routeCoordinates[routeCoordinates.length - 1])
        .addTo(map);

      map.on("load", () => {
        map?.addSource("xixi-route", {
          type: "geojson",
          data: {
            type: "Feature",
            properties: {},
            geometry: {
              type: "LineString",
              coordinates: routeCoordinates,
            },
          },
        });
        map?.addLayer({
          id: "xixi-route-shadow",
          type: "line",
          source: "xixi-route",
          paint: {
            "line-color": "#ffffff",
            "line-width": 9,
            "line-opacity": 0.9,
          },
        });
        map?.addLayer({
          id: "xixi-route",
          type: "line",
          source: "xixi-route",
          paint: {
            "line-color": "#00a870",
            "line-width": 5,
          },
        });
      });
    });

    return () => {
      cancelled = true;
      map?.remove();
    };
  }, [activeView, destination]);

  function selectView(view: AppView) {
    setActiveView(view);
    setNotice("");
    if (view !== "invoices") {
      setSelectedInvoiceTripId(null);
    }
  }

  function planTrip() {
    setStage("booking");
    setAssistantText(
      `收到，去${destination}。我已比较实时路况和接驾距离，舒适型现在性价比最好。`,
    );
    window.setTimeout(() => setStage("quotes"), 650);
  }

  function sendMessage(event: FormEvent) {
    event.preventDefault();
    const text = message.trim();
    if (!text) return;
    const nextDestination = text.replace(/^我想去|^去/, "") || text;
    setDestination(nextDestination);
    setAssistantText(`好的，正在为你规划去${nextDestination}的行程。`);
    setMessage("");
    setStage("booking");
    window.setTimeout(() => setStage("quotes"), 650);
  }

  function confirmRide() {
    if (stage === "booked") {
      selectView("trips");
      return;
    }
    const dueAt = new Date(
      Date.now() + selectedRide.etaMinutes * 60 * 1000,
    ).toISOString();
    const trip: TripRecord = {
      id: `XIXI-${Date.now().toString(36).toUpperCase()}`,
      origin: "故宫博物院 · 神武门",
      destination,
      vehicle: selectedRide.name,
      price: selectedRide.price,
      status: "driver_arriving",
      startedAt: new Date().toISOString(),
      durationMinutes: 28,
      distanceKilometers: 12.6,
      invoiceStatus: "unavailable",
    };
    setTrips((currentTrips) => [
      trip,
      ...currentTrips.filter(
        (item) =>
          item.status !== "driver_arriving" &&
          item.status !== "driver_arrived",
      ),
    ]);
    setDriverDueAt(dueAt);
    setStage("booked");
    setAssistantText(
      "已为你叫到车。王师傅正在前往上车点，车辆和司机信息已完成安全核验。",
    );
  }

  function cancelActiveTrip() {
    if (!activeTrip) return;
    setTrips((currentTrips) =>
      currentTrips.map((trip) =>
        trip.id === activeTrip.id
          ? { ...trip, status: "cancelled", invoiceStatus: "unavailable" }
          : trip,
      ),
    );
    setDriverDueAt(null);
    setStage("quotes");
    setAssistantText("行程已取消。需要重新规划时，随时告诉嘻嘻。");
    setNotice("当前行程已取消");
  }

  function completeDemoTrip() {
    if (!activeTrip) return;
    setTrips((currentTrips) =>
      currentTrips.map((trip) =>
        trip.id === activeTrip.id
          ? { ...trip, status: "completed", invoiceStatus: "available" }
          : trip,
      ),
    );
    setDriverDueAt(null);
    setStage("quotes");
    setNotice("行程已完成，现在可以申请电子发票");
  }

  function openInvoice(tripId: string) {
    setActiveView("invoices");
    setSelectedInvoiceTripId(tripId);
    setNotice("");
  }

  function requestInvoice(event: FormEvent) {
    event.preventDefault();
    if (!selectedInvoiceTripId || !invoiceEmail.trim()) return;
    const invoiceNumber = `XIXI${Date.now().toString().slice(-10)}`;
    setTrips((currentTrips) =>
      currentTrips.map((trip) =>
        trip.id === selectedInvoiceTripId
          ? {
              ...trip,
              invoiceStatus: "issued",
              invoiceNumber,
            }
          : trip,
      ),
    );
    setSelectedInvoiceTripId(null);
    setInvoiceEmail("");
    setNotice(
      `发票申请已记录，通知邮箱：${invoiceEmail.trim()}（演示版不会实际发送邮件）`,
    );
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <button
          className="brand brand-button"
          onClick={() => selectView("ride")}
          aria-label="返回嘻嘻出行首页"
        >
          <span className="brand-mark" aria-hidden="true">
            x
          </span>
          <span>
            <strong>嘻嘻出行</strong>
            <small>XIXI TRAVEL</small>
          </span>
        </button>

        <nav className="topnav" aria-label="主导航">
          {[
            ["ride", "打车"],
            ["trips", "行程"],
            ["invoices", "发票"],
          ].map(([view, label]) => (
            <button
              key={view}
              className={`nav-item ${
                activeView === view ? "nav-item-active" : ""
              }`}
              onClick={() => selectView(view as AppView)}
              aria-current={activeView === view ? "page" : undefined}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className="header-actions">
          <span className="live-clock" aria-label={`北京时间 ${currentClock}`}>
            <i aria-hidden="true" />
            {currentClock}
          </span>
          <button className="support-button" aria-label="安全中心">
            <span aria-hidden="true">⌾</span> 安全中心
          </button>
          <button className="profile-button" aria-label="个人中心">
            尹
          </button>
        </div>
      </header>

      {notice && (
        <div className="notice-toast" role="status">
          <span aria-hidden="true">✓</span>
          {notice}
          <button onClick={() => setNotice("")} aria-label="关闭提示">
            ×
          </button>
        </div>
      )}

      {activeView === "ride" && (
        <section className="workspace">
          <aside className="trip-panel">
            <div className="greeting">
              <span className="eyebrow">XIXI AI ASSISTANT</span>
              <h1>
                {greeting}，
                <br />
                今天想去哪儿？
              </h1>
              <p>告诉我目的地，剩下的交给嘻嘻。</p>
            </div>

            <div className="route-editor">
              <div className="route-rail" aria-hidden="true">
                <span className="route-dot route-dot-start" />
                <span className="route-line" />
                <span className="route-dot route-dot-end" />
              </div>
              <label className="place-field">
                <span>出发地</span>
                <input
                  defaultValue="故宫博物院 · 神武门"
                  aria-label="出发地"
                />
              </label>
              <label className="place-field">
                <span>目的地</span>
                <input
                  value={destination}
                  onChange={(event) => setDestination(event.target.value)}
                  aria-label="目的地"
                />
              </label>
              <button
                className="swap-button"
                aria-label="交换出发地和目的地"
              >
                ⇅
              </button>
            </div>

            <div className="suggestion-row" aria-label="快捷目的地">
              {["公司", "首都机场", "北京南站"].map((place) => (
                <button
                  key={place}
                  onClick={() => {
                    setDestination(place);
                    setAssistantText(`好的，已将目的地改为${place}。`);
                  }}
                >
                  {place}
                </button>
              ))}
            </div>

            <div className="assistant-card">
              <div className="assistant-head">
                <span className="xixi-avatar">x</span>
                <div>
                  <strong>嘻嘻</strong>
                  <span>智能出行助手</span>
                </div>
                <i>在线</i>
              </div>
              <p>{assistantText}</p>
              <div className="trip-facts">
                <span>
                  <small>预计里程</small>
                  <strong>12.6 km</strong>
                </span>
                <span>
                  <small>预计用时</small>
                  <strong>28 min</strong>
                </span>
                <span>
                  <small>预计到达</small>
                  <strong>{arrivalTime}</strong>
                </span>
              </div>
            </div>

            <form className="chat-bar" onSubmit={sendMessage}>
              <button
                type="button"
                className="voice-button"
                aria-label="语音输入"
              >
                ◉
              </button>
              <input
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                placeholder="告诉嘻嘻你的出行需求"
                aria-label="向嘻嘻发送消息"
              />
              <button type="submit" className="send-button" aria-label="发送">
                ↑
              </button>
            </form>
          </aside>

          <section className="map-panel" aria-label="行程地图">
            <div ref={mapContainer} className="map-canvas" />
            <div className="map-tint" aria-hidden="true" />
            <div className="map-top-row">
              <div className="arrival-card">
                <span className="pulse-dot" />
                <div>
                  <small>当前时间</small>
                  <strong>{currentClock}</strong>
                </div>
                <span className="arrival-divider" />
                <div>
                  <small>预计到达</small>
                  <strong>{arrivalTime}</strong>
                </div>
                <span className="arrival-divider" />
                <div>
                  <small>实时路况</small>
                  <strong className="traffic-good">畅通</strong>
                </div>
              </div>
              <button className="locate-button" aria-label="定位到当前位置">
                ◎
              </button>
            </div>

            <div className="quote-dock">
              <div className="quote-heading">
                <div>
                  <span>嘻嘻已为你找到</span>
                  <h2>
                    {stage === "booking"
                      ? "正在刷新附近车辆…"
                      : "3 种出行方案"}
                  </h2>
                </div>
                <button className="more-button">更多车型</button>
              </div>

              <div
                className={`ride-list ${
                  stage === "booking" ? "is-loading" : ""
                }`}
              >
                {rideOptions.map((option) => (
                  <button
                    type="button"
                    key={option.name}
                    className={`ride-card ${
                      rideType === option.name ? "ride-card-selected" : ""
                    }`}
                    onClick={() => setRideType(option.name)}
                    aria-pressed={rideType === option.name}
                  >
                    <span className="car-visual" aria-hidden="true">
                      <i className="car-roof" />
                      <i className="car-body" />
                      <i className="car-wheel car-wheel-left" />
                      <i className="car-wheel car-wheel-right" />
                    </span>
                    <span className="ride-copy">
                      <span>
                        <strong>{option.name}</strong>
                        <i>{option.badge}</i>
                      </span>
                      <small>{option.description}</small>
                      <em>{option.seats} 座 · 含基础保障</em>
                    </span>
                    <span className="ride-price">
                      <strong>¥{option.price}</strong>
                      <small>{option.eta}</small>
                    </span>
                  </button>
                ))}
              </div>

              <div className="booking-row">
                <button
                  className="plan-button"
                  onClick={
                    stage === "booked"
                      ? () => selectView("trips")
                      : planTrip
                  }
                  disabled={!destination || stage === "booking"}
                >
                  {stage === "booking"
                    ? "嘻嘻正在规划"
                    : stage === "booked"
                      ? "查看行程"
                      : `呼叫${selectedRide.name}`}
                </button>
                <button className="book-button" onClick={confirmRide}>
                  {stage === "booked"
                    ? "司机正在赶来"
                    : `确认 ¥${selectedRide.price}`}
                </button>
              </div>
            </div>

            {stage === "booked" && (
              <div className="driver-card" role="status">
                <button
                  className="driver-close"
                  onClick={() => selectView("trips")}
                  aria-label="查看行程详情"
                >
                  ›
                </button>
                <span className="driver-avatar">王</span>
                <div className="driver-info">
                  <small>
                    {driverCountdown > 0
                      ? "司机距你 1.2 公里"
                      : "司机已到达上车点"}
                  </small>
                  <strong>京 A·X7288</strong>
                  <span>白色新能源 · 王师傅 · 4.9 分</span>
                </div>
                <div className="driver-eta">
                  <strong>{formatCountdown(driverCountdown)}</strong>
                  <span>{driverCountdown > 0 ? "后到达" : "请上车"}</span>
                </div>
              </div>
            )}

            <a
              className="osm-attribution"
              href="https://www.openstreetmap.org/copyright"
              target="_blank"
              rel="noreferrer"
            >
              © OpenStreetMap contributors
            </a>
          </section>
        </section>
      )}

      {activeView === "trips" && (
        <section className="account-page">
          <div className="page-heading">
            <div>
              <span className="eyebrow">MY JOURNEYS</span>
              <h1>我的行程</h1>
              <p>当前订单和历史记录都在这里，状态会随行程实时更新。</p>
            </div>
            <button
              className="primary-action"
              onClick={() => selectView("ride")}
            >
              ＋ 再叫一辆车
            </button>
          </div>

          <div className="summary-grid">
            <article>
              <span>本月行程</span>
              <strong>{trips.length} 次</strong>
              <small>较上月节省约 38 分钟</small>
            </article>
            <article>
              <span>本月里程</span>
              <strong>
                {trips
                  .reduce(
                    (total, trip) => total + trip.distanceKilometers,
                    0,
                  )
                  .toFixed(1)}{" "}
                km
              </strong>
              <small>含当前行程</small>
            </article>
            <article>
              <span>累计支出</span>
              <strong>
                ¥
                {trips.reduce((total, trip) => total + trip.price, 0)}
              </strong>
              <small>电子行程单可随时查看</small>
            </article>
          </div>

          <div className="records-layout">
            <section className="records-panel">
              <div className="section-title">
                <div>
                  <span>TRIP RECORDS</span>
                  <h2>全部行程</h2>
                </div>
                <small>数据保存在当前设备</small>
              </div>

              <div className="trip-record-list">
                {trips.map((trip) => (
                  <article
                    className={`trip-record ${
                      trip.status === "driver_arriving" ||
                      trip.status === "driver_arrived"
                        ? "trip-record-active"
                        : ""
                    }`}
                    key={trip.id}
                  >
                    <div className="record-time">
                      <strong>
                        {tripDateFormatter.format(new Date(trip.startedAt))}
                      </strong>
                      <span>{trip.id}</span>
                    </div>
                    <div className="record-route">
                      <span className="record-route-dot" />
                      <div>
                        <strong>{trip.origin}</strong>
                        <i />
                        <strong>{trip.destination}</strong>
                      </div>
                    </div>
                    <div className="record-meta">
                      <span>{trip.vehicle}</span>
                      <span>{trip.distanceKilometers} km</span>
                      <span>{trip.durationMinutes} 分钟</span>
                    </div>
                    <div className="record-price">
                      <span
                        className={`status-chip status-${trip.status}`}
                      >
                        {statusLabel(trip.status)}
                      </span>
                      <strong>¥{trip.price}</strong>
                    </div>
                    <div className="record-actions">
                      {(trip.status === "driver_arriving" ||
                        trip.status === "driver_arrived") && (
                        <>
                          <button
                            className="secondary-action"
                            onClick={() => selectView("ride")}
                          >
                            返回地图
                          </button>
                          <button
                            className="secondary-action"
                            onClick={cancelActiveTrip}
                          >
                            取消行程
                          </button>
                          <button
                            className="primary-small"
                            onClick={completeDemoTrip}
                          >
                            完成演示行程
                          </button>
                        </>
                      )}
                      {trip.status === "completed" &&
                        trip.invoiceStatus === "available" && (
                          <button
                            className="primary-small"
                            onClick={() => openInvoice(trip.id)}
                          >
                            申请发票
                          </button>
                        )}
                      {trip.status === "completed" &&
                        trip.invoiceStatus === "issued" && (
                          <button
                            className="secondary-action"
                            onClick={() => openInvoice(trip.id)}
                          >
                            查看发票
                          </button>
                        )}
                    </div>
                  </article>
                ))}
              </div>
            </section>

            <aside className="help-panel">
              <span className="xixi-avatar">x</span>
              <h2>行程有问题？</h2>
              <p>
                嘻嘻可以帮你查询订单状态、解释费用，或在行程开始前取消订单。
              </p>
              <button onClick={() => selectView("ride")}>
                返回对话询问
              </button>
            </aside>
          </div>
        </section>
      )}

      {activeView === "invoices" && (
        <section className="account-page">
          <div className="page-heading">
            <div>
              <span className="eyebrow">E-INVOICE</span>
              <h1>行程发票</h1>
              <p>选择已完成的行程，填写接收邮箱即可登记开票申请。</p>
            </div>
            <div className="invoice-total">
              <span>可开票金额</span>
              <strong>
                ¥
                {invoiceTrips
                  .filter((trip) => trip.invoiceStatus === "available")
                  .reduce((total, trip) => total + trip.price, 0)}
              </strong>
            </div>
          </div>

          <div className="invoice-layout">
            <section className="records-panel">
              <div className="section-title">
                <div>
                  <span>INVOICEABLE TRIPS</span>
                  <h2>可开票行程</h2>
                </div>
                <small>{invoiceTrips.length} 条记录</small>
              </div>

              <div className="invoice-list">
                {invoiceTrips.map((trip) => (
                  <article className="invoice-record" key={trip.id}>
                    <div className="invoice-icon" aria-hidden="true">
                      票
                    </div>
                    <div className="invoice-copy">
                      <strong>
                        {trip.origin} → {trip.destination}
                      </strong>
                      <span>
                        {tripDateFormatter.format(
                          new Date(trip.startedAt),
                        )}{" "}
                        · {trip.vehicle} · {trip.id}
                      </span>
                      {trip.invoiceNumber && (
                        <small>申请编号：{trip.invoiceNumber}</small>
                      )}
                    </div>
                    <div className="invoice-state">
                      <strong>¥{trip.price}</strong>
                      <span
                        className={`invoice-chip invoice-${trip.invoiceStatus}`}
                      >
                        {invoiceLabel(trip.invoiceStatus)}
                      </span>
                    </div>
                    <button
                      className={
                        trip.invoiceStatus === "available"
                          ? "primary-small"
                          : "secondary-action"
                      }
                      onClick={() =>
                        trip.invoiceStatus === "available"
                          ? setSelectedInvoiceTripId(trip.id)
                          : setNotice(
                              `发票申请 ${trip.invoiceNumber ?? ""} 已登记`,
                            )
                      }
                    >
                      {trip.invoiceStatus === "available"
                        ? "申请开票"
                        : "查看详情"}
                    </button>
                  </article>
                ))}
              </div>
            </section>

            <aside className="invoice-side">
              {selectedInvoiceTripId ? (
                <form className="invoice-form" onSubmit={requestInvoice}>
                  <div className="form-heading">
                    <span>开票信息</span>
                    <button
                      type="button"
                      onClick={() => setSelectedInvoiceTripId(null)}
                      aria-label="关闭开票表单"
                    >
                      ×
                    </button>
                  </div>
                  <label>
                    发票抬头
                    <input
                      value={invoiceTitle}
                      onChange={(event) =>
                        setInvoiceTitle(event.target.value)
                      }
                      required
                    />
                  </label>
                  <label>
                    接收邮箱
                    <input
                      type="email"
                      value={invoiceEmail}
                      onChange={(event) =>
                        setInvoiceEmail(event.target.value)
                      }
                      placeholder="name@example.com"
                      required
                    />
                  </label>
                  <div className="selected-invoice-trip">
                    <span>本次开票金额</span>
                    <strong>
                      ¥
                      {trips.find(
                        (trip) => trip.id === selectedInvoiceTripId,
                      )?.price ?? 0}
                    </strong>
                  </div>
                  <button className="invoice-submit" type="submit">
                    确认提交开票申请
                  </button>
                  <small>
                    提交即表示确认开票信息无误。演示数据仅保存在当前设备，不会实际发送邮件。
                  </small>
                </form>
              ) : (
                <div className="invoice-guide">
                  <span className="invoice-guide-mark">票</span>
                  <h2>电子发票</h2>
                  <p>
                    选择左侧“可开票”行程开始申请。已提交的申请可以再次查看登记状态。
                  </p>
                  <ul>
                    <li>仅支持已完成行程</li>
                    <li>金额以行程结算为准</li>
                    <li>演示版不会实际发送邮件</li>
                  </ul>
                </div>
              )}
            </aside>
          </div>
        </section>
      )}
    </main>
  );
}
