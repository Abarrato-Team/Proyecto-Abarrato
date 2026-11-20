const message = document.getElementById("message");
const map = L.map("map", { attributionControl: false });
L.control.attribution({ prefix: false }).addTo(map);
const tiles = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
}).addTo(map);
L.control.scale({ imperial: false }).addTo(map);
tiles.on("tileerror", () => {
    message.textContent = "No se pudo cargar parte del mapa.";
    message.hidden = false;
});
tiles.on("loading", () => {
    message.hidden = true;
});

const shops = L.layerGroup().addTo(map);
let area;
let user;
let pins = [];
let selected;

function popup(text) {
    const label = document.createElement("span");
    label.textContent = text;
    return label;
}

window.updateNearbyMap = (latitude, longitude, radius, results, recenter) => {
    const center = [latitude, longitude];
    if (!user || recenter || (map.getBounds().contains(user.getLatLng()) && !map.getBounds().contains(center))) {
        map.fitBounds(L.latLng(center).toBounds(radius * 2), { padding: [18, 18], maxZoom: 15 });
    }
    if (!user) {
        area = L.circle(center, {
            radius, color: "#1c704f", weight: 2, fillOpacity: 0.07, interactive: false
        }).addTo(map);
        user = L.circleMarker(center, {
            radius: 8, color: "#fff", weight: 3, fillColor: "#1265cd", fillOpacity: 1
        }).bindPopup("Tu ubicación").addTo(map);
        message.hidden = true;
    } else {
        area.setLatLng(center);
        user.setLatLng(center);
    }

    const shown = selected;
    shops.clearLayers();
    pins = results.map((store, index) => {
        const icon = L.divIcon({
            className: "shop-pin " + String(store.comercio || "").toLowerCase().replace("_", "-")
                + (index === 0 ? " nearest" : ""),
            html: String(index + 1),
            iconSize: [26, 26],
            iconAnchor: [13, 13]
        });
        const marker = L.marker([store.lat, store.lon], { icon, title: store.name })
            .bindPopup(popup(store.name + " · " + store.distance))
            .addTo(shops);
        marker.on("popupopen", () => { selected = store.id; });
        marker.on("popupclose", () => { if (selected === store.id) selected = null; });
        if (shown === store.id) marker.openPopup();
        return marker;
    });
};

window.selectNearbyStore = index => {
    const pin = pins[index];
    if (pin) {
        map.panTo(pin.getLatLng());
        pin.openPopup();
    }
};
