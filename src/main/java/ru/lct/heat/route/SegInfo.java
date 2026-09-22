package ru.lct.heat.route;

import java.util.ArrayList;
import java.util.List;

/** Допустимый прямой отрезок трассы: длина и спецучастки вдоль него. */
public final class SegInfo {

    /** Спецучасток [from, to] метров от начала отрезка с коэффициентом k. */
    public static final class Special {
        public final double from;
        public final double to;
        public final double k;
        public final String type;
        /** Существующая линия сети для пересечения heat_network, иначе -1. */
        public final int line;
        /** Точка пересечения линейной коммуникации (м от начала отрезка) или середина перехода. */
        public final double at;

        public Special(double from, double to, double k, String type) {
            this(from, to, k, type, -1, (from + to) / 2);
        }

        public Special(double from, double to, double k, String type, int line, double at) {
            this.from = from;
            this.to = to;
            this.k = k;
            this.type = type;
            this.line = line;
            this.at = at;
        }
    }

    public final double length;
    public final List<Special> specials;

    public SegInfo(double length, List<Special> specials) {
        this.length = length;
        this.specials = specials;
    }

    /**
     * Разбиение отрезка на части с постоянным набором спецпроходов (R-RST-08):
     * на наложении берётся max(K), на границах начинается новая часть.
     * Возвращает [from, to, k, маска типов] для каждой части; k = 1 - обычная прокладка.
     */
    public List<Part> parts() {
        List<Part> out = new ArrayList<>();
        if (specials.isEmpty()) {
            out.add(new Part(0, length, 1.0, ""));
            return out;
        }
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(length);
        for (Special s : specials) {
            cuts.add(clamp(s.from));
            cuts.add(clamp(s.to));
        }
        cuts.sort(Double::compare);
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double a = cuts.get(i);
            double b = cuts.get(i + 1);
            if (b - a < 1e-6) {
                continue;
            }
            double mid = (a + b) / 2;
            double k = 1.0;
            StringBuilder types = new StringBuilder();
            for (Special s : specials) {
                if (mid >= s.from && mid <= s.to) {
                    k = Math.max(k, s.k);
                    if (types.indexOf(s.type) < 0) {
                        types.append(types.length() == 0 ? "" : ",").append(s.type);
                    }
                }
            }
            Part last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.types.contentEquals(types) && last.k == k) {
                out.set(out.size() - 1, new Part(last.from, b, k, last.types));
            } else {
                out.add(new Part(a, b, k, types.toString()));
            }
        }
        return out;
    }

    private double clamp(double v) {
        return Math.max(0, Math.min(length, v));
    }

    public static final class Part {
        public final double from;
        public final double to;
        public final double k;
        public final String types;

        Part(double from, double to, double k, String types) {
            this.from = from;
            this.to = to;
            this.k = k;
            this.types = types;
        }

        public double length() {
            return to - from;
        }

        public boolean special() {
            return !types.isEmpty();
        }
    }
}
