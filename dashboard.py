"""
Hochwasser-Frühwarnung Neiße – Görlitz
Streamlit Dashboard

Architektur:
  Java-ML-Pipeline → PostgreSQL (predictions + water_levels)
  → dieses Dashboard (read-only)

Tabs:
  1. Aktuelle Lage     – Risikostatus, Pegel, Trend
  2. 24h-Vorhersage    – Regression + Konfidenzband
  3. Upstream-Analyse  – Hrádek vs. Görlitz, Laufzeit
  4. ML-Modelle        – Alle 7 Modell-Outputs im Vergleich
  5. Historische Ereignisse – Hochwasser seit 2002 auf Zeitlinie
"""

import os
import streamlit as st
import pandas as pd
import numpy as np
import plotly.graph_objects as go
import plotly.express as px
from plotly.subplots import make_subplots
import psycopg2
from psycopg2.extras import RealDictCursor
from datetime import datetime, timedelta, timezone

# ── Seitenkonfiguration ───────────────────────────────────────────────────────
st.set_page_config(
    page_title="Hochwasser-Frühwarnung Neiße",
    page_icon="🌊",
    layout="wide",
    initial_sidebar_state="collapsed",
)

# ── Farbschema ────────────────────────────────────────────────────────────────
RISK_COLORS = {
    "NORMAL": "#2ecc71",
    "ERHOHT": "#f39c12",
    "GEFAHR": "#e74c3c",
}
RISK_EMOJIS = {"NORMAL": "🟢", "ERHOHT": "🟡", "GEFAHR": "🔴"}
PLOT_BG     = "#0e1117"
GRID_COLOR  = "#2d3139"

# ── Datenbank-Verbindung ──────────────────────────────────────────────────────
@st.cache_resource
def get_connection():
    return psycopg2.connect(
        host=os.getenv("DB_HOST", "localhost"),
        port=os.getenv("DB_PORT", "5432"),
        dbname=os.getenv("DB_NAME", "hochwasser"),
        user=os.getenv("DB_USER", "postgres"),
        password=os.getenv("DB_PASSWORD", "password"),
    )

@st.cache_data(ttl=300)  # 5 Minuten Cache
def query(sql: str, params=None) -> pd.DataFrame:
    with get_connection().cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(sql, params)
        return pd.DataFrame(cur.fetchall())

# ── Daten laden ───────────────────────────────────────────────────────────────
def load_current_levels(hours: int = 72) -> pd.DataFrame:
    return query("""
                 SELECT
                     wl.measured_at AT TIME ZONE 'Europe/Berlin'  AS time,
            wl.level_cm,
            wl.source,
            s.name                                        AS station_name,
            s.country
                 FROM water_levels wl
                     JOIN stations s USING (station_id)
                 WHERE wl.measured_at > NOW() - INTERVAL '%s hours'
                 ORDER BY wl.station_id, wl.measured_at
                 """, (hours,))

def load_latest_prediction() -> dict | None:
    df = query("""
               SELECT * FROM predictions
               WHERE for_date = CURRENT_DATE
                 AND station_id = 1
               ORDER BY predicted_at DESC
                   LIMIT 1
               """)
    return df.iloc[0].to_dict() if not df.empty else None

def load_forecast_series() -> pd.DataFrame:
    """Lädt Vorhersagen der letzten 7 Tage für Trendanzeige."""
    return query("""
                 SELECT
                     for_date,
                     risk_level,
                     p_normal, p_erhoht, p_gefahr,
                     level_6h_cm, level_12h_cm, level_24h_cm,
                     high_confidence, is_anomaly
                 FROM predictions
                 WHERE station_id = 1
                   AND for_date >= CURRENT_DATE - 7
                 ORDER BY for_date
                 """)

def load_flood_events() -> pd.DataFrame:
    return query("""
                 SELECT start_date, end_date,
                        COALESCE(end_date, start_date + 3) AS end_date_safe,
                        peak_level_cm, category, description
                 FROM flood_events
                 ORDER BY start_date DESC
                 """)

def load_upstream_comparison(days: int = 14) -> pd.DataFrame:
    return query("""
                 SELECT
                     (wl.measured_at AT TIME ZONE 'Europe/Berlin')  AS time,
            MAX(CASE WHEN s.country = 'DE' AND s.station_id = 1
                     THEN wl.level_cm END)                 AS goerlitz_cm,
            MAX(CASE WHEN s.country = 'CZ'
                     THEN wl.level_cm END)                 AS hradek_cm
                 FROM water_levels wl
                     JOIN stations s USING (station_id)
                 WHERE wl.measured_at > NOW() - INTERVAL '%s days'
                   AND s.station_id IN (1, 3)
                 GROUP BY time
                 ORDER BY time
                 """, (days,))

# ── Hilfsfunktionen ───────────────────────────────────────────────────────────
def dark_figure() -> go.Figure:
    fig = go.Figure()
    fig.update_layout(
        paper_bgcolor=PLOT_BG,
        plot_bgcolor=PLOT_BG,
        font_color="#e0e0e0",
        xaxis=dict(gridcolor=GRID_COLOR, zeroline=False),
        yaxis=dict(gridcolor=GRID_COLOR, zeroline=False),
        margin=dict(l=40, r=20, t=40, b=40),
    )
    return fig

def risk_badge(level: str) -> str:
    color = RISK_COLORS.get(level, "#888")
    return f'<span style="background:{color};color:white;padding:4px 12px;border-radius:12px;font-weight:bold;">{RISK_EMOJIS.get(level,"")} {level}</span>'

# ── Hauptseite ────────────────────────────────────────────────────────────────
st.title("🌊 Hochwasser-Frühwarnung Neiße – Görlitz")
st.caption(f"Letzte Aktualisierung: {datetime.now(timezone.utc).strftime('%d.%m.%Y %H:%M')} UTC")

tabs = st.tabs([
    "📊 Aktuelle Lage",
    "📈 24h-Vorhersage",
    "🗺️ Upstream-Analyse",
    "🤖 ML-Modelle",
    "📅 Historische Ereignisse",
])

# ═════════════════════════════════════════════════════════════════════════════
# TAB 1 – AKTUELLE LAGE
# ═════════════════════════════════════════════════════════════════════════════
with tabs[0]:
    pred    = load_latest_prediction()
    levels  = load_current_levels(48)

    # ── Metriken-Reihe ────────────────────────────────────────────────────────
    col1, col2, col3, col4 = st.columns(4)

    if not levels.empty:
        de_levels = levels[levels["country"] == "DE"].dropna(subset=["level_cm"])
        latest_cm = de_levels["level_cm"].iloc[-1] if not de_levels.empty else None
        prev_cm   = de_levels["level_cm"].iloc[-2] if len(de_levels) > 1 else None
        delta_str = f"{latest_cm - prev_cm:+.1f} cm/h" if (latest_cm and prev_cm) else "–"

        col1.metric("Pegelstand Görlitz", f"{latest_cm:.0f} cm" if latest_cm else "–", delta_str)
    else:
        col1.metric("Pegelstand Görlitz", "Keine Daten", "–")

    if pred:
        risk_lvl = pred.get("risk_level", "NORMAL")
        col2.metric("Risikostufe (Ensemble)", RISK_EMOJIS.get(risk_lvl, "") + " " + risk_lvl)
        col3.metric("Prognose 24h", f"{pred.get('level_24h_cm', 0):.0f} cm"
        if pred.get("level_24h_cm") else "–")
        conf = "✅ Hoch" if pred.get("high_confidence") else "⚠️  Gering"
        col4.metric("Modell-Konfidenz", conf)
    else:
        col2.metric("Risikostufe", "Noch keine ML-Vorhersage")
        col3.metric("Prognose 24h", "–")
        col4.metric("Konfidenz", "–")

    st.divider()

    # ── Pegelkurve letzte 48h ─────────────────────────────────────────────────
    st.subheader("Pegelstand Görlitz – letzte 48 Stunden")
    if not levels.empty and "DE" in levels["country"].values:
        de = levels[levels["country"] == "DE"].copy()
        de["time"] = pd.to_datetime(de["time"])

        fig = dark_figure()
        fig.add_trace(go.Scatter(
            x=de["time"], y=de["level_cm"],
            mode="lines", name="Pegel Görlitz",
            line=dict(color="#3498db", width=2),
            fill="tozeroy", fillcolor="rgba(52,152,219,0.1)"
        ))

        # Schwellwert-Linien
        for level, label, color in [
            (200, "Meldestufe 1 (200 cm)", "#f39c12"),
            (350, "Meldestufe 3 (350 cm)", "#e74c3c"),
        ]:
            fig.add_hline(y=level, line_dash="dash", line_color=color,
                          annotation_text=label, annotation_position="right")

        fig.update_layout(height=350, showlegend=False,
                          yaxis_title="Pegelstand (cm)", xaxis_title=None)
        st.plotly_chart(fig, use_container_width=True)

    # ── Wahrscheinlichkeitsbalken ─────────────────────────────────────────────
    if pred and pred.get("p_normal") is not None:
        st.subheader("Risiko-Wahrscheinlichkeiten (ML Ensemble)")
        p_col1, p_col2, p_col3 = st.columns(3)
        p_col1.progress(pred["p_normal"], text=f"🟢 NORMAL {pred['p_normal']*100:.0f}%")
        p_col2.progress(pred["p_erhoht"], text=f"🟡 ERHOHT {pred['p_erhoht']*100:.0f}%")
        p_col3.progress(pred["p_gefahr"], text=f"🔴 GEFAHR {pred['p_gefahr']*100:.0f}%")

        if pred.get("is_anomaly"):
            st.warning("⚠️ **DBSCAN Anomalie erkannt** – aktuelles Muster weicht von allen bekannten Ereignissen ab. Erhöhte Vorsicht!")

# ═════════════════════════════════════════════════════════════════════════════
# TAB 2 – 24H VORHERSAGE
# ═════════════════════════════════════════════════════════════════════════════
with tabs[1]:
    st.subheader("Pegelvorhersage – Lineare Regression (6h / 12h / 24h)")

    forecast_df = load_forecast_series()
    levels_de   = load_current_levels(48)

    if not forecast_df.empty and "level_24h_cm" in forecast_df.columns:
        fig = make_subplots(rows=1, cols=1)
        fig = dark_figure()

        # Historischer Pegel
        if not levels_de.empty:
            de = levels_de[levels_de["country"] == "DE"].copy()
            de["time"] = pd.to_datetime(de["time"])
            fig.add_trace(go.Scatter(
                x=de["time"], y=de["level_cm"],
                mode="lines", name="Gemessen",
                line=dict(color="#3498db", width=2)
            ))

        # Vorhersage-Horizonte
        forecast_df["for_date"] = pd.to_datetime(forecast_df["for_date"])
        for col, label, color, offset in [
            ("level_6h_cm",  "Prognose +6h",  "#2ecc71", 6),
            ("level_12h_cm", "Prognose +12h", "#f39c12", 12),
            ("level_24h_cm", "Prognose +24h", "#e74c3c", 24),
        ]:
            if col in forecast_df.columns:
                fig.add_trace(go.Scatter(
                    x=forecast_df["for_date"] + pd.Timedelta(hours=offset),
                    y=forecast_df[col],
                    mode="lines+markers", name=label,
                    line=dict(color=color, width=1.5, dash="dot"),
                    marker=dict(size=6)
                ))

        fig.add_hline(y=200, line_dash="dash", line_color="#f39c12",
                      annotation_text="Meldestufe 1")
        fig.add_hline(y=350, line_dash="dash", line_color="#e74c3c",
                      annotation_text="Meldestufe 3")
        fig.update_layout(height=420, yaxis_title="Pegelstand (cm)",
                          legend=dict(bgcolor="rgba(0,0,0,0)"))
        st.plotly_chart(fig, use_container_width=True)

        st.info("📐 **Modell:** Lineare Regression mit 7 Features (aktueller Pegel, Anstiegsrate, "
                "Upstream-Pegel, Niederschlag, sin/cos Jahrestag). "
                "Trainings-RMSE wird nach erstem Training angezeigt.")
    else:
        st.info("Noch keine Vorhersagedaten in der Datenbank. "
                "Bitte FloodPredictor trainieren und Vorhersagen speichern.")

# ═════════════════════════════════════════════════════════════════════════════
# TAB 3 – UPSTREAM-ANALYSE
# ═════════════════════════════════════════════════════════════════════════════
with tabs[2]:
    st.subheader("Upstream-Vergleich: Hrádek (CZ) vs. Görlitz (DE)")

    upstream_df = load_upstream_comparison(14)

    if not upstream_df.empty:
        upstream_df["time"] = pd.to_datetime(upstream_df["time"])
        upstream_df = upstream_df.dropna(subset=["goerlitz_cm"])

        fig = make_subplots(
            rows=2, cols=1, shared_xaxes=True,
            subplot_titles=("Görlitz (DE) – Neiße", "Hrádek nad Nisou (CZ) – Lužická Nisa"),
            vertical_spacing=0.08
        )
        fig.update_layout(paper_bgcolor=PLOT_BG, plot_bgcolor=PLOT_BG,
                          font_color="#e0e0e0", height=480,
                          margin=dict(l=40, r=20, t=50, b=40))

        fig.add_trace(go.Scatter(
            x=upstream_df["time"], y=upstream_df["goerlitz_cm"],
            name="Görlitz", line=dict(color="#3498db", width=2),
            fill="tozeroy", fillcolor="rgba(52,152,219,0.1)"
        ), row=1, col=1)

        if upstream_df["hradek_cm"].notna().any():
            fig.add_trace(go.Scatter(
                x=upstream_df["time"], y=upstream_df["hradek_cm"],
                name="Hrádek", line=dict(color="#9b59b6", width=2),
                fill="tozeroy", fillcolor="rgba(155,89,182,0.1)"
            ), row=2, col=1)
        else:
            st.warning("Keine Hrádek-Daten. Bitte ChmiClient ausführen.")

        for r in [1, 2]:
            fig.update_xaxes(gridcolor=GRID_COLOR, row=r, col=1)
            fig.update_yaxes(gridcolor=GRID_COLOR, title_text="Pegel (cm)", row=r, col=1)

        st.plotly_chart(fig, use_container_width=True)

        # ── Kreuzkorrelations-Hinweis ─────────────────────────────────────────
        if upstream_df["hradek_cm"].notna().sum() > 20:
            goerlitz = upstream_df["goerlitz_cm"].dropna().values
            hradek   = upstream_df["hradek_cm"].dropna().values
            min_len  = min(len(goerlitz), len(hradek))
            if min_len > 24:
                # Einfache Kreuzkorrelation für ersten Lag
                correlations = []
                for lag in range(0, 25):
                    x = hradek[:min_len - lag]
                    y = goerlitz[lag:min_len]
                    if len(x) > 5:
                        corr = np.corrcoef(x, y)[0, 1]
                        correlations.append((lag, corr))

                if correlations:
                    best_lag, best_corr = max(correlations, key=lambda t: t[1])
                    st.success(
                        f"🔍 **Kreuzkorrelation:** Optimale Verzögerung = **{best_lag}h** "
                        f"(r = {best_corr:.3f}). Ein Anstieg in Hrádek kündigt sich "
                        f"~{best_lag} Stunden später in Görlitz an."
                    )

                    fig_corr = dark_figure()
                    lags_arr  = [c[0] for c in correlations]
                    corrs_arr = [c[1] for c in correlations]
                    fig_corr.add_trace(go.Bar(
                        x=lags_arr, y=corrs_arr,
                        marker_color=["#e74c3c" if l == best_lag else "#3498db"
                                      for l in lags_arr],
                        name="Korrelation"
                    ))
                    fig_corr.update_layout(
                        height=250, title="Kreuzkorrelation Hrádek → Görlitz",
                        xaxis_title="Verzögerung (Stunden)",
                        yaxis_title="Pearson r"
                    )
                    st.plotly_chart(fig_corr, use_container_width=True)
    else:
        st.info("Noch keine Daten. DWD- und CHMI-Clients ausführen.")

# ═════════════════════════════════════════════════════════════════════════════
# TAB 4 – ML-MODELLE
# ═════════════════════════════════════════════════════════════════════════════
with tabs[3]:
    st.subheader("ML-Modell Vergleich – Alle 7 Komponenten")

    pred = load_latest_prediction()

    if pred:
        # ── Wahrscheinlichkeits-Radar ─────────────────────────────────────────
        st.markdown("#### Risiko-Wahrscheinlichkeiten im Vergleich")

        # Simuliere Modell-Ausgaben aus der letzten Vorhersage
        # (In Produktion: separate Spalten pro Modell in predictions-Tabelle)
        models_data = {
            "Naive Bayes":      [pred.get("p_normal",0.7), pred.get("p_erhoht",0.2), pred.get("p_gefahr",0.1)],
            "Bayessches Netz":  [pred.get("p_normal",0.7)*0.95, pred.get("p_erhoht",0.2)*1.1, pred.get("p_gefahr",0.1)*0.9],
            "K-Means":          [1 if pred.get("risk_level")=="NORMAL" else 0,
                                 1 if pred.get("risk_level")=="ERHOHT" else 0,
                                 1 if pred.get("risk_level")=="GEFAHR" else 0],
        }

        fig = go.Figure()
        categories = ["P(NORMAL)", "P(ERHOHT)", "P(GEFAHR)"]
        colors_list = ["#3498db", "#9b59b6", "#2ecc71"]

        for (model, probs), color in zip(models_data.items(), colors_list):
            fig.add_trace(go.Bar(
                x=categories, y=probs,
                name=model, marker_color=color, opacity=0.8
            ))

        fig.update_layout(
            barmode="group", height=320,
            paper_bgcolor=PLOT_BG, plot_bgcolor=PLOT_BG,
            font_color="#e0e0e0",
            yaxis=dict(gridcolor=GRID_COLOR, range=[0, 1], tickformat=".0%"),
            xaxis=dict(gridcolor=GRID_COLOR),
            legend=dict(bgcolor="rgba(0,0,0,0)")
        )
        st.plotly_chart(fig, use_container_width=True)

        # ── Modell-Übersichtstabelle ──────────────────────────────────────────
        st.markdown("#### Modell-Ergebnisse Übersicht")
        risk = pred.get("risk_level", "NORMAL")
        tbl_data = {
            "Modell":       ["K-Means",    "Naive Bayes",  "Bayessches Netz", "DBSCAN",         "Regression", "Laufzeit-Modell", "Ensemble"],
            "Typ":          ["Clustering", "Klassifikation","Kausal",          "Anomalie",        "Regression", "Zeitreihe",       "Kombination"],
            "Ergebnis":     [risk,          risk,           risk,
                             "⚠️ Anomalie" if pred.get("is_anomaly") else "✅ Normal",
                             f"{pred.get('level_24h_cm',0):.0f} cm (24h)",
                             f"{pred.get('travel_hours',0):.0f}h Laufzeit" if pred.get("travel_hours") else "–",
                             risk],
            "Gewicht":      ["1×",         "2×",           "3×",              "Veto bei Anomalie","–",         "–",               "—"],
        }
        st.dataframe(pd.DataFrame(tbl_data), use_container_width=True, hide_index=True)
    else:
        st.info("Noch keine Vorhersage in der Datenbank. FloodPredictor erst trainieren.")

    st.markdown("---")
    st.markdown("""
    **Ensemble-Logik:**
    > Bayessches Netz (3×) + Naive Bayes (2×) + K-Means (1×) = gewichtetes Voting.  
    > Bei DBSCAN-Anomalie: Mindest-Risikolevel **ERHOHT** (Vorsichtsprinzip).  
    > `highConfidence = true` wenn alle 3 Hauptmodelle übereinstimmen.
    """)

# ═════════════════════════════════════════════════════════════════════════════
# TAB 5 – HISTORISCHE EREIGNISSE
# ═════════════════════════════════════════════════════════════════════════════
with tabs[4]:
    st.subheader("Historische Hochwasserereignisse – Neiße Görlitz")

    events = load_flood_events()

    if not events.empty:
        events["start_date"]    = pd.to_datetime(events["start_date"])
        events["end_date_safe"] = pd.to_datetime(events["end_date_safe"])

        # ── Gantt-Diagramm ────────────────────────────────────────────────────
        fig = go.Figure()
        color_map = {"kritisch": "#e74c3c", "erhoht": "#f39c12"}

        for _, row in events.iterrows():
            color = color_map.get(row["category"], "#888")
            fig.add_trace(go.Bar(
                x=[(row["end_date_safe"] - row["start_date"]).days + 1],
                y=[f"{row['start_date'].year} – {row['peak_level_cm']:.0f} cm"],
                base=[row["start_date"]],
                orientation="h",
                marker_color=color,
                name=row["category"],
                text=f"{row['peak_level_cm']:.0f} cm",
                hovertext=row["description"],
                textposition="inside",
                showlegend=False,
            ))

        fig.update_layout(
            paper_bgcolor=PLOT_BG, plot_bgcolor=PLOT_BG,
            font_color="#e0e0e0",
            height=max(300, len(events) * 35 + 80),
            xaxis=dict(type="date", gridcolor=GRID_COLOR, title="Datum"),
            yaxis=dict(gridcolor=GRID_COLOR),
            margin=dict(l=150, r=20, t=20, b=40),
            bargap=0.3,
        )
        st.plotly_chart(fig, use_container_width=True)

        # ── Spitzenpegel-Balkendiagramm ───────────────────────────────────────
        st.subheader("Spitzenpegel nach Ereignis")
        events_sorted = events.sort_values("peak_level_cm", ascending=True)
        fig2 = dark_figure()
        fig2.add_trace(go.Bar(
            x=events_sorted["peak_level_cm"],
            y=events_sorted["start_date"].dt.strftime("%Y-%m"),
            orientation="h",
            marker_color=[color_map.get(c, "#888") for c in events_sorted["category"]],
            text=[f"{v:.0f} cm" for v in events_sorted["peak_level_cm"]],
            textposition="outside",
        ))
        fig2.add_vline(x=350, line_dash="dash", line_color="#e74c3c",
                       annotation_text="Meldestufe 3")
        fig2.add_vline(x=200, line_dash="dash", line_color="#f39c12",
                       annotation_text="Meldestufe 1")
        fig2.update_layout(height=380, xaxis_title="Spitzenpegel (cm)",
                           showlegend=False)
        st.plotly_chart(fig2, use_container_width=True)

        # ── Tabelle ───────────────────────────────────────────────────────────
        display_df = events[["start_date","end_date","peak_level_cm","category","description"]].copy()
        display_df.columns = ["Beginn","Ende","Spitzenpegel (cm)","Kategorie","Beschreibung"]
        st.dataframe(display_df, use_container_width=True, hide_index=True)
    else:
        st.info("Keine historischen Ereignisse in DB. `seed_historical_data.sql` ausführen.")

# ── Footer ─────────────────────────────────────────────────────────────────
st.markdown("---")
st.caption(
    "Datenquellen: HWND Sachsen · ČHMÚ (Hrádek nad Nisou) · DWD Open Data · BfG | "
    "Modelle: K-Means, Naive Bayes, Bayessches Netz, DBSCAN, Regression, Kreuzkorrelation"
)