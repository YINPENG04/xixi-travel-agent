"use client";

import { FormEvent, useEffect, useRef, useState } from "react";
import "maplibre-gl/dist/maplibre-gl.css";

type RideType = "轻享" | "舒适" | "六座";
type Stage = "quotes" | "booking" | "booked";

const rideOptions = [
  {
    name: "轻享" as RideType,
    description: "经济实惠 · 预计 3 分钟接驾",
    price: 42,
    seats: 4,
    eta: "3 分钟",
    badge: "省 ¥8",
  },
  {
    name: "舒适" as RideType,
    description: "宽敞安静 · 预计 5 分钟接驾",
    price: 58,
    seats: 4,
    eta: "5 分钟",
    badge: "推荐",
  },
  {
    name: "六座" as RideType,
    description: "多人同行 · 预计 7 分钟接驾",
    price: 76,
    seats: 6,
    eta: "7 分钟",
    badge: "空间大",
  },
];

const routeCoordinates: [number, number][] = [
  [116.3977, 39.9087],
  [116.393, 39.899],
  [116.389, 39.887],
  [116.386, 39.873],
  [116.3846, 39.8652],
];

export function XixiTravelApp() {
  const mapContainer = useRef<HTMLDivElement>(null);
  const [rideType, setRideType] = useState<RideType>("舒适");
  const [stage, setStage] = useState<Stage>("quotes");
  const [destination, setDestination] = useState("北京南站");
  const [message, setMessage] = useState("");
  const [assistantText, setAssistantText] = useState(
    "路线已规划好。现在出发约 28 分钟，我为你避开了前三门附近的拥堵路段。",
  );

  const selectedRide =
    rideOptions.find((option) => option.name === rideType) ?? rideOptions[1];

  useEffect(() => {
    if (!mapContainer.current) return;

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
  }, [destination]);

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
    setDestination(text.replace(/^我想去|^去/, "") || text);
    setAssistantText(`好的，正在为你规划去${text.replace(/^我想去|^去/, "")}的行程。`);
    setMessage("");
    planTrip();
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="#" aria-label="嘻嘻出行首页">
          <span className="brand-mark" aria-hidden="true">
            x
          </span>
          <span>
            <strong>嘻嘻出行</strong>
            <small>XIXI TRAVEL</small>
          </span>
        </a>

        <nav className="topnav" aria-label="主导航">
          <button className="nav-item nav-item-active">打车</button>
          <button className="nav-item">行程</button>
          <button className="nav-item">发票</button>
        </nav>

        <div className="header-actions">
          <button className="support-button" aria-label="安全中心">
            <span aria-hidden="true">⌾</span> 安全中心
          </button>
          <button className="profile-button" aria-label="个人中心">
            尹
          </button>
        </div>
      </header>

      <section className="workspace">
        <aside className="trip-panel">
          <div className="greeting">
            <span className="eyebrow">XIXI AI ASSISTANT</span>
            <h1>
              晚上好，
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
              <input defaultValue="故宫博物院 · 神武门" aria-label="出发地" />
            </label>
            <label className="place-field">
              <span>目的地</span>
              <input
                value={destination}
                onChange={(event) => setDestination(event.target.value)}
                aria-label="目的地"
              />
            </label>
            <button className="swap-button" aria-label="交换出发地和目的地">
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
                <small>红绿灯</small>
                <strong>14 个</strong>
              </span>
            </div>
          </div>

          <form className="chat-bar" onSubmit={sendMessage}>
            <button type="button" className="voice-button" aria-label="语音输入">
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
                <small>预计到达</small>
                <strong>21:06</strong>
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
                <h2>{stage === "booking" ? "正在刷新附近车辆…" : "3 种出行方案"}</h2>
              </div>
              <button className="more-button">更多车型</button>
            </div>

            <div className={`ride-list ${stage === "booking" ? "is-loading" : ""}`}>
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
                onClick={planTrip}
                disabled={!destination || stage === "booking"}
              >
                {stage === "booking"
                  ? "嘻嘻正在规划"
                  : stage === "booked"
                    ? "查看行程"
                    : `呼叫${selectedRide.name}`}
              </button>
              <button
                className="book-button"
                onClick={() => {
                  setStage("booked");
                  setAssistantText(
                    "已为你叫到车。王师傅正在前往上车点，车辆和司机信息已完成安全核验。",
                  );
                }}
              >
                {stage === "booked" ? "司机正在赶来" : `确认 ¥${selectedRide.price}`}
              </button>
            </div>
          </div>

          {stage === "booked" && (
            <div className="driver-card" role="status">
              <button
                className="driver-close"
                onClick={() => setStage("quotes")}
                aria-label="关闭司机信息"
              >
                ×
              </button>
              <span className="driver-avatar">王</span>
              <div className="driver-info">
                <small>司机距你 1.2 公里</small>
                <strong>京 A·X7288</strong>
                <span>白色新能源 · 王师傅 · 4.9 分</span>
              </div>
              <div className="driver-eta">
                <strong>3</strong>
                <span>分钟</span>
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
    </main>
  );
}
