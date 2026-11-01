package com.villagertalk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** All the "understanding" lives here: keyword intents + small conversation memory. */
final class Brain {
    enum Mood { CALM, HAPPY, SAD, ANGRY, SCARED }
    enum Q { HOW_ARE_YOU, EMERALDS, LIKE_VILLAGE, MORE_JOKE, FAV_FOOD, FAMILY, SAFE, PROPOSAL }

    record Pending(Q q, long expires) {}
    record JokeState(int stage, long expires) {}
    record Ctx(ServerPlayer p, Entity v, Persona me, ServerLevel sl, String raw, long now) {}
    record Spouse(UUID id, String name, boolean female, boolean player) {}

    static final Random R = new Random();
    static final Map<UUID, Persona> personas = new HashMap<>();
    static final Map<UUID, Pending> pending = new HashMap<>();
    static final Map<UUID, JokeState> jokes = new HashMap<>();
    static final Map<UUID, Integer> deaths = new HashMap<>();
    static final Set<String> usedNames = new HashSet<>();
    static int villagerDeaths = 0;

    static final String[] MALE = {
        "Bob", "Gus", "Otto", "Fenwick", "Barnaby", "Cuthbert", "Hector", "Rufus", "Silas", "Orville", "Percy",
        "Ambrose", "Walt", "Ellis", "Milo", "Harold", "Basil", "Clyde", "Dewey", "Felix", "Gilbert", "Horace",
        "Ignatius", "Jasper", "Leopold", "Monty", "Norman", "Oswald", "Pip", "Quentin", "Reggie", "Stanley",
        "Thaddeus", "Victor", "Wendell", "Archie"
    };
    static final String[] FEMALE = {
        "Edna", "Mabel", "Hilda", "Dora", "Greta", "Winnie", "Pearl", "Agnes", "Maude", "Tilly", "Nora", "Lottie",
        "Beatrice", "Clara", "Daisy", "Elsie", "Flora", "Gertie", "Hattie", "Iris", "Josie", "Kitty", "Lulu",
        "Mildred", "Nell", "Olive", "Penny", "Queenie", "Rosie", "Sadie", "Trudy", "Violet", "Wilma", "Ivy", "Ada",
        "Bertha"
    };

    static final String[] INSULTS = {
        "stupid", "idiot", "dumb", "ugly", "useless", "trash", "worthless", "moron", "fat", "lazy", "smelly",
        "weird", "loser", "liar", "coward", "annoying", "clumsy", "boring", "rude", "jerk", "stinky", "fool"
    };
    static final String[] COMPLIMENTS = {
        "beautiful", "handsome", "pretty", "nice", "kind", "lovely", "smart", "clever", "brave", "wonderful",
        "funny", "cute", "strong", "amazing", "great", "sweet", "gorgeous"
    };
    static final String[] COMEBACKS = {
        "turnip-head", "cabbage-brain", "soggy-bread", "clumsy-golem", "mud-footed", "potato-nose"
    };

    static final String[][] JOKES = {
        {"Why did the villager go back to school?", "To get a little more emerald-ucation! Hrmm hrmm!"},
        {"What is a villager's favourite kind of music?", "Hrmm and blues!"},
        {"Why did the iron golem visit the doctor?", "He had a rusty throat!"},
        {"Why don't villagers play hide and seek?", "Nobody can hide with a nose this big!"},
        {"How does a creeper say hello?", "Ssssup!"},
        {"Why did the Enderman get kicked out of class?", "He kept staring at the teacher!"},
        {"What do you call a villager who sells tools?", "A very sharp dealer!"},
        {"Why was the skeleton so calm at the village?", "He had no nerves!"},
        {"Why did the zombie not enter my house?", "He lost his brains over the door price!"},
        {"What did the farmer villager say to the carrot?", "You are looking rooted and ready!"}
    };

    // ------------------------------------------------------------ tags (saved with the villager)

    static String tagVal(Entity e, String prefix) {
        for (String t : e.getTags()) {
            if (t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return null;
    }

    static void clearTag(Entity e, String prefix) {
        for (String t : new ArrayList<>(e.getTags())) {
            if (t.startsWith(prefix)) e.removeTag(t);
        }
    }

    static void setTag(Entity e, String prefix, String val) {
        clearTag(e, prefix);
        e.addTag(prefix + val);
    }

    static int intTag(Entity e, String prefix, int def) {
        String s = tagVal(e, prefix);
        if (s == null) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------ personality

    static final class Persona {
        String name;
        boolean female;
        Mood mood = Mood.CALM;
        long moodUntil;
        int trust;
        int coins;
        UUID follow;
        boolean staying;
        final Set<String> grudges = new HashSet<>();

        Persona(Entity v) {
            String g = tagVal(v, "vt_g|");
            boolean first = g == null;
            if (first) {
                female = R.nextBoolean();
                setTag(v, "vt_g|", female ? "f" : "m");
            } else {
                female = g.equals("f");
            }

            String cn = (v.hasCustomName() && v.getCustomName() != null) ? v.getCustomName().getString() : null;
            String chosen = cn;
            if (chosen == null || chosen.isBlank() || usedNames.contains(chosen.toLowerCase(Locale.ROOT))) {
                chosen = freshName(female);
            }
            name = chosen;
            usedNames.add(name.toLowerCase(Locale.ROOT));
            if (!name.equals(cn)) v.setCustomName(Component.literal(name));

            trust = intTag(v, "vt_trust|", 0);
            coins = 2 + R.nextInt(45);

            if (first) {
                setTag(v, "vt_mom|", randomName(true));
                setTag(v, "vt_dad|", randomName(false));
                setTag(v, "vt_kids|", "0");
                if (R.nextDouble() < 0.3) setTag(v, "vt_single|", "1");
            }
        }

        Mood mood(long now) {
            if (mood != Mood.CALM && now > moodUntil) mood = Mood.CALM;
            return mood;
        }

        void setMood(Mood m, long now, long duration) {
            mood = m;
            moodUntil = now + duration;
        }
    }

    static Persona persona(Entity v) {
        return personas.computeIfAbsent(v.getUUID(), k -> new Persona(v));
    }

    static String randomName(boolean female) {
        String[] pool = female ? FEMALE : MALE;
        return pool[R.nextInt(pool.length)];
    }

    static String freshName(boolean female) {
        List<String> pool = new ArrayList<>(Arrays.asList(female ? FEMALE : MALE));
        Collections.shuffle(pool, R);
        for (String n : pool) {
            if (!usedNames.contains(n.toLowerCase(Locale.ROOT))) return n;
        }
        String base = randomName(female);
        int i = 2;
        while (usedNames.contains((base + " " + i).toLowerCase(Locale.ROOT))) i++;
        return base + " " + i;
    }

    static void addTrust(Entity v, Persona me, int d) {
        me.trust = Math.max(-10, Math.min(20, me.trust + d));
        setTag(v, "vt_trust|", String.valueOf(me.trust));
    }

    static boolean isBaby(Entity e) {
        return e instanceof LivingEntity le && le.isBaby();
    }

    // ------------------------------------------------------------ marriage

    static Spouse spouseOf(Entity v) {
        String s = tagVal(v, "vt_sp|");
        if (s != null) {
            String[] a = s.split("\\|", 3);
            if (a.length == 3) {
                try {
                    return new Spouse(UUID.fromString(a[0]), a[1], a[2].equals("f"), false);
                } catch (IllegalArgumentException ex) {
                    // fall through
                }
            }
        }
        String p = tagVal(v, "vt_spp|");
        if (p != null) {
            String[] a = p.split("\\|", 2);
            if (a.length == 2) {
                try {
                    return new Spouse(UUID.fromString(a[0]), a[1], false, true);
                } catch (IllegalArgumentException ex) {
                    // fall through
                }
            }
        }
        return null;
    }

    static boolean marriedToPlayer(Entity v, ServerPlayer p) {
        Spouse sp = spouseOf(v);
        return sp != null && sp.player() && sp.id().equals(p.getUUID());
    }

    /** Pair two villagers so BOTH remember each other (names match both ways). */
    static void marryVillagers(Entity a, Entity b) {
        Persona pa = persona(a);
        Persona pb = persona(b);
        int kids = R.nextInt(4);
        setTag(a, "vt_sp|", b.getUUID() + "|" + pb.name + "|" + (pb.female ? "f" : "m"));
        setTag(b, "vt_sp|", a.getUUID() + "|" + pa.name + "|" + (pa.female ? "f" : "m"));
        setTag(a, "vt_kids|", String.valueOf(kids));
        setTag(b, "vt_kids|", String.valueOf(kids));
        clearTag(a, "vt_lost|");
        clearTag(b, "vt_lost|");
        shareHome(a, b);
    }

    /** Make two villagers use the same bed / house (best effort). */
    static void shareHome(Entity a, Entity b) {
        if (!(a instanceof LivingEntity la) || !(b instanceof LivingEntity lb)) return;
        try {
            Optional<GlobalPos> ha = la.getBrain().getMemory(MemoryModuleType.HOME);
            Optional<GlobalPos> hb = lb.getBrain().getMemory(MemoryModuleType.HOME);
            if (ha.isPresent()) {
                if (hb.isEmpty() || !hb.get().equals(ha.get())) {
                    lb.getBrain().setMemory(MemoryModuleType.HOME, ha.get());
                }
            } else if (hb.isPresent()) {
                la.getBrain().setMemory(MemoryModuleType.HOME, hb.get());
            }
        } catch (RuntimeException ex) {
            // never crash the game because of house sharing
        }
    }

    static void shareWithSpouse(ServerLevel sl, Entity e) {
        Spouse sp = spouseOf(e);
        if (sp == null || sp.player()) return;
        Entity other = sl.getEntity(sp.id());
        if (other != null && other.isAlive()) shareHome(e, other);
    }

    // ---------------------------------------------------------------- helpers

    static String pick(String... options) {
        return options[R.nextInt(options.length)];
    }

    static String norm(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    static boolean w(Set<String> tok, String... words) {
        for (String x : words) if (tok.contains(x)) return true;
        return false;
    }

    static boolean s(Set<String> tok, String... stems) {
        for (String t : tok) for (String x : stems) if (t.startsWith(x)) return true;
        return false;
    }

    static boolean has(String pad, String... phrases) {
        for (String x : phrases) if (pad.contains(" " + x + " ")) return true;
        return false;
    }

    static String firstOf(Set<String> tok, String[] list) {
        for (String x : list) if (tok.contains(x)) return x;
        return null;
    }

    static boolean yes(Set<String> tok) {
        return w(tok, "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "definitely", "absolutely", "course", "ya");
    }

    static boolean no(Set<String> tok) {
        return w(tok, "no", "nope", "nah", "never", "nothing");
    }

    static boolean pos(Set<String> tok, String pad) {
        return has(pad, "not bad") || w(tok, "good", "great", "fine", "well", "happy", "awesome", "amazing",
                "nice", "excellent", "fantastic", "wonderful");
    }

    static boolean neg(Set<String> tok, String pad) {
        if (has(pad, "not bad")) return false;
        return w(tok, "bad", "sad", "terrible", "awful", "tired", "angry", "sick", "hurt", "lonely", "scared",
                "horrible", "worse", "depressed", "upset");
    }

    /** True for things like "Bob said you are stupid" (so the listener is NOT the person named). */
    static boolean looksLikeGossip(String raw) {
        String t = norm(raw);
        Set<String> tok = new HashSet<>(Arrays.asList(t.split(" ")));
        String pad = " " + t + " ";
        boolean accuse = has(pad, "said", "says", "called", "calls", "told", "thinks", "think", "insulted",
                "insults", "saying", "telling");
        boolean you = w(tok, "you", "your", "youre", "ya", "u");
        return accuse && you && (firstOf(tok, INSULTS) != null || firstOf(tok, COMPLIMENTS) != null);
    }

    static String pre(Persona me, long now) {
        switch (me.mood(now)) {
            case ANGRY: return pick("Hmph. ", "Grr. ", "*scowls* ");
            case SAD: return pick("*sighs* ", "...", "*sniffles* ");
            case SCARED: return pick("Eep! ", "*trembles* ", "*looks around nervously* ");
            case HAPPY: return pick("Hehe! ", "Hrmm hrmm! ", "*beams* ");
            default: return pick("Hrmm. ", "", "", "");
        }
    }

    static int countNear(Ctx c, EntityType<?> type, double r) {
        return c.sl().getEntities((Entity) null, c.v().getBoundingBox().inflate(r),
                e -> e.getType() == type && e.isAlive()).size();
    }

    static int emeraldsOf(ServerPlayer p) {
        int total = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            if (st.is(Items.EMERALD)) total += st.getCount();
            else if (st.is(Items.EMERALD_BLOCK)) total += 9 * st.getCount();
        }
        return total;
    }

    static String tierOf(ItemStack st) {
        if (st.isEmpty()) return "";
        String id = st.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String t : new String[] {"netherite", "diamond", "iron", "golden", "chainmail", "copper", "stone",
                "wooden", "leather", "turtle"}) {
            if (id.contains(t)) return t;
        }
        return "";
    }

    static String kindOf(ItemStack st) {
        String id = st.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String k : new String[] {"sword", "pickaxe", "axe", "shovel", "hoe", "crossbow", "bow", "trident",
                "mace", "spear", "shield", "fishing_rod", "elytra", "helmet", "chestplate", "leggings", "boots"}) {
            if (id.contains(k)) return k;
        }
        return "";
    }

    static void face(Entity v, Entity target) {
        double dx = target.getX() - v.getX();
        double dz = target.getZ() - v.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        v.setYRot(yaw);
        v.setYHeadRot(yaw);
    }

    static Entity findNamed(Ctx c, String pad, Entity exclude) {
        for (Entity e : VillagerTalk.villagersNear(c.sl(), c.v(), 48)) {
            if (e.getUUID().equals(exclude.getUUID())) continue;
            Persona pe = persona(e);
            if (pad.contains(" " + pe.name.toLowerCase(Locale.ROOT) + " ")) return e;
        }
        return null;
    }

    static void later(int delay, Runnable r) {
        VillagerTalk.tasks.add(new VillagerTalk.Task(VillagerTalk.tick + delay, r));
    }

    // ----------------------------------------------------------------- gossip

    static String gossipInsult(Ctx c, Entity accused, String insult) {
        Persona me = c.me();
        Persona ap = persona(accused);
        Entity v = c.v();
        ServerLevel sl = c.sl();

        Spouse sp = spouseOf(v);
        if (sp != null && !sp.player() && sp.id().equals(accused.getUUID())) {
            addTrust(v, me, -1);
            return "My own " + (ap.female ? "wife " : "husband ") + ap.name + " would never say that! You are lying!";
        }

        me.setMood(Mood.ANGRY, c.now(), 6000);
        me.grudges.add(ap.name);
        me.follow = null;
        me.staying = false;
        Mob mob = v instanceof Mob mm ? mm : null;
        if (mob != null) mob.setNoAi(false);

        // walk over to the accused and confront them
        later(50, () -> {
            if (mob != null && v.isAlive() && accused.isAlive()) {
                mob.getNavigation().moveTo(accused.getX(), accused.getY(), accused.getZ(), 1.1D);
            }
        });
        later(200, () -> {
            if (!v.isAlive() || !accused.isAlive()) return;
            face(v, accused);
            VillagerTalk.say(sl, v, ap.name + "! Did you call me \"" + insult + "\"?! How dare you!", 0);
            if (R.nextDouble() < 0.55) {
                VillagerTalk.say(sl, accused, "What?! I never said that, " + me.name
                        + "! Somebody is lying to you!", 50);
                VillagerTalk.say(sl, v, "Hrmm... then who is spreading rumours? I will find out.", 110);
            } else {
                ap.setMood(Mood.ANGRY, VillagerTalk.tick, 6000);
                ap.grudges.add(me.name);
                VillagerTalk.say(sl, accused, "So what if I did?! You ARE a bit of a "
                        + COMEBACKS[R.nextInt(COMEBACKS.length)] + ", " + me.name + "!", 50);
                VillagerTalk.say(sl, v, "Hmph! This is not over, " + ap.name + "!", 110);
            }
        });
        return pick(ap.name + " called me \"" + insult + "\"?! Hrmm! I will have words with " + ap.name + "!",
                ap.name + " said THAT about me?! I am going to find " + (ap.female ? "her" : "him") + "!");
    }

    static String gossipCompliment(Ctx c, Entity accused, String word) {
        Persona me = c.me();
        Persona ap = persona(accused);
        Entity v = c.v();
        ServerLevel sl = c.sl();
        me.setMood(Mood.HAPPY, c.now(), 4000);
        me.grudges.remove(ap.name);
        Mob mob = v instanceof Mob mm ? mm : null;
        if (mob != null && !me.staying) {
            later(50, () -> {
                if (v.isAlive() && accused.isAlive()) {
                    mob.getNavigation().moveTo(accused.getX(), accused.getY(), accused.getZ(), 1.0D);
                }
            });
        }
        later(200, () -> {
            if (!v.isAlive() || !accused.isAlive()) return;
            face(v, accused);
            VillagerTalk.say(sl, v, ap.name + ", thank you for calling me " + word + "!", 0);
            VillagerTalk.say(sl, accused, "Hrmm? Oh... yes. Of course I did. *blushes*", 50);
        });
        return "Aww, " + ap.name + " said I am " + word + "? That is so sweet! I will go thank " + ap.name + ".";
    }

    // --------------------------------------------------------------- marriage

    static String proposal(Ctx c) {
        Entity v = c.v();
        Persona me = c.me();
        ServerPlayer p = c.p();
        String pn = p.getName().getString();

        if (isBaby(v)) return "I am just a child! Hrmm! Ask me when I grow up.";

        Spouse sp = spouseOf(v);
        if (sp != null) {
            if (sp.player() && sp.id().equals(p.getUUID())) return "We are already married, silly! Hrmm hrmm.";
            return "I am already married to " + sp.name() + "! What would they say?!";
        }
        String wed = tagVal(p, "vt_wed|");
        if (wed != null) {
            String[] a = wed.split("\\|", 2);
            return "You are already married to " + (a.length > 1 ? a[1] : "someone")
                    + "! Say 'divorce' to them first. Hrmm.";
        }
        int need = tagVal(v, "vt_single|") != null ? 5 : 3;
        if (me.trust < need) {
            return "We barely know each other, " + pn + ". Talk to me, make me laugh, be kind... (bond "
                    + Math.max(0, me.trust) + "/" + need + ")";
        }
        pending.put(p.getUUID(), new Pending(Q.PROPOSAL, c.now() + 2400));
        return "Oh my... " + pn + ", do you truly want to marry me? Say yes!";
    }

    static String marryPlayer(Ctx c) {
        Entity v = c.v();
        Persona me = c.me();
        ServerPlayer p = c.p();
        String pn = p.getName().getString();
        if (spouseOf(v) != null || tagVal(p, "vt_wed|") != null) return "Hrmm... something has changed. Not today.";

        setTag(v, "vt_spp|", p.getUUID() + "|" + pn);
        clearTag(v, "vt_single|");
        setTag(v, "vt_kids|", "0");
        setTag(p, "vt_wed|", v.getUUID() + "|" + me.name);
        me.setMood(Mood.HAPPY, c.now(), 12000);

        Component msg = Component.literal(pn + " and " + me.name + " are now married! Congratulations!")
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        for (ServerPlayer pl : c.sl().players()) pl.sendSystemMessage(msg);
        return "YES! Hrmm hrmm hrmm! I will marry you, " + pn + ", my love!";
    }

    static String divorce(Ctx c) {
        Entity v = c.v();
        ServerPlayer p = c.p();
        if (!marriedToPlayer(v, p)) return "We are not even married! Hrmm?";
        clearTag(v, "vt_spp|");
        clearTag(p, "vt_wed|");
        c.me().setMood(Mood.SAD, c.now(), 12000);
        addTrust(v, c.me(), -5);
        return "*sobs* Then it is over... I thought we were happy.";
    }

    // --------------------------------------------------------------- questions

    static String ask(ServerPlayer p, Persona me, long now) {
        Q[] pool = {Q.HOW_ARE_YOU, Q.EMERALDS, Q.LIKE_VILLAGE, Q.FAV_FOOD, Q.FAMILY, Q.SAFE};
        Q q = pool[R.nextInt(pool.length)];
        pending.put(p.getUUID(), new Pending(q, now + 2400));
        String pn = p.getName().getString();
        switch (q) {
            case EMERALDS: return "Hrmm, do you have any spare emeralds, " + pn + "? Business has been slow.";
            case LIKE_VILLAGE: return "Tell me honestly, do you like our village?";
            case FAV_FOOD: return "What is your favourite food, " + pn + "? I am a bread man myself.";
            case FAMILY: return "Do you have family waiting for you somewhere?";
            case SAFE: return "Have you seen any zombies or pillagers on your way here?";
            default: return "How are you holding up today, " + pn + "?";
        }
    }

    static String answerQuestion(Q q, Ctx c, Set<String> tok, String t, String pad) {
        boolean yes = yes(tok), no = no(tok);
        Persona me = c.me();
        String r = answerInner(q, c, tok, t, pad, yes, no);
        if (r != null && q != Q.PROPOSAL && q != Q.MORE_JOKE) addTrust(c.v(), me, 1);
        return r;
    }

    private static String answerInner(Q q, Ctx c, Set<String> tok, String t, String pad, boolean yes, boolean no) {
        Persona me = c.me();
        switch (q) {
            case PROPOSAL:
                if (yes || has(pad, "i do")) return marryPlayer(c);
                if (no) return "Oh... I understand. Maybe another time.";
                return null;
            case HOW_ARE_YOU:
                if (neg(tok, pad) || (no && !yes)) {
                    return pick("Oh dear. Do you want to talk about it? I am listening.",
                            "Hrmm. Rest near a bed, friend. Tomorrow is a new day.");
                }
                if (pos(tok, pad) || yes) {
                    me.setMood(Mood.HAPPY, c.now(), 3000);
                    return pick("Wonderful! Happiness is catching, you know.", "Hrmm! That makes me smile too.");
                }
                return null;
            case EMERALDS:
                if (yes) {
                    if (emeraldsOf(c.p()) == 0) return "Hrmm... your pockets say otherwise. Cheeky one!";
                    return "Splendid! Right-click me and let us trade. I will give you a fair deal.";
                }
                if (no) return "Hrmm... then we are both poor. Go mine something shiny!";
                return null;
            case LIKE_VILLAGE:
                if (yes || pos(tok, pad)) return "Wonderful! It is the villagers who keep it standing. Hrmm.";
                if (no || neg(tok, pad)) return "Oh... what would make it better? More beds? More golems?";
                return null;
            case FAV_FOOD: {
                String food = t.length() > 30 ? t.substring(0, 30) : t;
                if (s(tok, "bread", "carrot", "potato", "beetroot", "pie", "cake", "steak", "apple", "cookie")) {
                    return "Hrmm, " + food + "! A person of taste. I would trade three emeralds for one bite.";
                }
                return "\"" + food + "\"? Hrmm, interesting. I will remember that.";
            }
            case FAMILY:
                if (yes) return "Warm hearts! Family is worth more than any emerald.";
                if (no) return "Then the village is your family now. You are always welcome here.";
                return null;
            case SAFE:
                if (yes) {
                    me.setMood(Mood.SCARED, c.now(), 3000);
                    return "Zombies?! Stay close to the iron golems, please!";
                }
                if (no) return "Phew. Keep it that way, friend.";
                return null;
            case MORE_JOKE:
                if (yes) return tellJoke(c);
                if (no) return "Hrmm, tough crowd.";
                return null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ jokes

    static String tellJoke(Ctx c) {
        String[] j = JOKES[R.nextInt(JOKES.length)];
        VillagerTalk.say(c.sl(), c.v(), j[1], 60);
        pending.put(c.p().getUUID(), new Pending(Q.MORE_JOKE, c.now() + 2400));
        return j[0];
    }

    static String reactToJoke(Ctx c) {
        c.me().setMood(Mood.HAPPY, c.now(), 3000);
        addTrust(c.v(), c.me(), 1);
        if (R.nextDouble() < 0.7) {
            return pick("Hahaha! Hrmm hrmm hrmm! That is a good one!",
                    "*snorts* Oh, that got me. Hrmm!",
                    "Ha! I will tell that one to the whole village!");
        }
        return pick("Hrmm... I do not get it. But I laugh anyway. Ha.",
                "*groans* That was terrible. Tell me another!",
                "Hrmm. My grandmother tells better ones.");
    }

    // ----------------------------------------------------------- instructions

    static String instruct(Ctx c, Set<String> tok, String pad) {
        Persona me = c.me();
        ServerPlayer p = c.p();
        Entity v = c.v();
        Mob mob = v instanceof Mob mm ? mm : null;

        boolean unfollow = has(pad, "stop following", "dont follow", "unfollow", "stop follow", "go home", "go away");
        boolean follow = !unfollow && (w(tok, "follow") || has(pad, "come with me"));
        boolean stay = has(pad, "wait here", "dont move", "stand still", "stay here")
                || w(tok, "stay", "freeze");
        boolean release = has(pad, "you can move", "move again", "you are free", "relax")
                || w(tok, "unfreeze");
        boolean come = has(pad, "come here", "come to me", "come over", "come closer", "over here");
        boolean jump = w(tok, "jump", "hop");
        boolean dance = w(tok, "dance", "spin", "twirl");
        boolean look = has(pad, "look at me", "face me", "turn around", "look here");

        if (!(unfollow || follow || stay || release || come || jump || dance || look)) return null;

        Mood m = me.mood(c.now());
        boolean beloved = marriedToPlayer(v, p);
        if (!beloved && (m == Mood.ANGRY || m == Mood.SCARED || me.trust < -2) && R.nextDouble() < 0.8) {
            return pick("No. After what happened, I do not take orders from you.",
                    "Hrmm... not in the mood. Leave me be.");
        }

        if (unfollow) {
            me.follow = null;
            me.staying = false;
            if (mob != null) {
                mob.setNoAi(false);
                mob.getNavigation().stop();
            }
            return pick("Alright, I will stop following. Hrmm.", "Back to my own business, then.");
        }
        if (follow) {
            if (mob == null) return null;
            mob.setNoAi(false);
            me.staying = false;
            me.follow = p.getUUID();
            return pick("Lead the way! I will follow, " + p.getName().getString() + ".",
                    "Hrmm! An adventure! Right behind you.");
        }
        if (stay) {
            me.follow = null;
            me.staying = true;
            if (mob != null) {
                mob.getNavigation().stop();
                mob.setNoAi(true);
            }
            return pick("Staying right here. Not moving an inch.", "Hrmm. I will wait here.");
        }
        if (release) {
            me.staying = false;
            if (mob != null) mob.setNoAi(false);
            return "Ahh, my legs! Thank you. Hrmm.";
        }
        if (come) {
            if (mob == null) return null;
            mob.setNoAi(false);
            mob.getNavigation().moveTo(p.getX(), p.getY(), p.getZ(), 1.1D);
            return "Coming, coming!";
        }
        if (jump) {
            if (mob == null) return null;
            mob.setNoAi(false);
            mob.getJumpControl().jump();
            return pick("Hup!", "Is this high enough? Hrmm.");
        }
        if (look) {
            face(v, p);
            return "Hrmm? Yes? I am looking.";
        }
        // dance
        for (int i = 1; i <= 24; i++) {
            final int step = i;
            later(i * 2, () -> {
                if (!v.isAlive()) return;
                float yaw = v.getYRot() + 40.0F;
                v.setYRot(yaw);
                v.setYHeadRot(yaw);
                if (step % 6 == 0 && mob != null) mob.getJumpControl().jump();
            });
        }
        me.setMood(Mood.HAPPY, c.now(), 3000);
        return "Watch this! Hrmm hrmm hrmm!";
    }

    // ------------------------------------------------------------- main entry

    static String respond(Ctx c) {
        String t = norm(c.raw());
        if (t.isEmpty()) return null;
        Set<String> tok = new HashSet<>(Arrays.asList(t.split(" ")));
        String pad = " " + t + " ";
        UUID id = c.p().getUUID();
        Entity v = c.v();
        Persona me = c.me();
        long now = c.now();
        String pn = c.p().getName().getString();
        String pre = pre(me, now);

        // 1. Did the player just answer a question the villager asked?
        Pending pq = pending.remove(id);
        if (pq != null && now <= pq.expires() && tok.size() <= 8) {
            String r = answerQuestion(pq.q(), c, tok, t, pad);
            if (r != null) return pre + r;
        }

        // 2. Is the player in the middle of telling a joke?
        JokeState js = jokes.remove(id);
        if (js != null && now <= js.expires()) {
            if (js.stage() == 1) {
                jokes.put(id, new JokeState(2, now + 1200));
                return t + " who?";
            }
            return reactToJoke(c);
        }
        boolean knock = pad.startsWith(" knock knock");
        boolean riddle = has(pad, "why did the", "why do the", "what do you call a", "what do you call an",
                "what did the") && pad.length() > 20;
        if (knock) {
            jokes.put(id, new JokeState(1, now + 1200));
            return "Who is there?";
        }
        if (riddle) {
            jokes.put(id, new JokeState(3, now + 1200));
            return pick("Hrmm... I give up. Why?", "Oooh, a riddle! I do not know... tell me!");
        }

        // 3. Gossip: "Bob said you are stupid" (the villager believes it even if it is a lie)
        boolean accuse = has(pad, "said", "says", "called", "calls", "told", "thinks", "think", "insulted",
                "insults", "saying", "telling");
        if (accuse && w(tok, "you", "your", "youre", "ya", "u")) {
            Entity accused = findNamed(c, pad, v);
            if (accused != null) {
                String ins = firstOf(tok, INSULTS);
                String comp = firstOf(tok, COMPLIMENTS);
                if (ins != null) return gossipInsult(c, accused, ins);
                if (comp != null) return gossipCompliment(c, accused, comp);
            }
        }

        // 4. Marriage with the player
        if (has(pad, "marry me", "will you marry", "wanna marry", "want to marry", "want to get married",
                "get married", "be my wife", "be my husband", "be my spouse", "be my partner", "lets marry",
                "i propose", "marry you")) {
            return pre + proposal(c);
        }
        if (w(tok, "divorce") || has(pad, "break up", "we are over", "i dump you")) {
            return divorce(c);
        }

        // 5. Orders
        String order = instruct(c, tok, pad);
        if (order != null) return order;

        // 6. Villager jokes
        if (has(pad, "tell me a joke", "make me laugh", "another joke", "know any jokes", "say something funny")
                || w(tok, "joke", "jokes", "funny")) {
            return tellJoke(c);
        }
        if (s(tok, "haha", "hehe", "lol", "lmao", "rofl") || has(pad, "that was funny", "good one")) {
            me.setMood(Mood.HAPPY, now, 2400);
            pending.put(id, new Pending(Q.MORE_JOKE, now + 2400));
            return "Hrmm hrmm! I know, I am hilarious. Want another joke?";
        }

        // 7. Help
        if (w(tok, "help", "commands") || has(pad, "what can you do", "what do you understand")) {
            return "Talk to me! Ask about money, family, marriage, death, my feelings, your gear or armour, the village. "
                    + "Order me: follow me, stay, come here, jump, dance, look at me. Or tell me a joke! "
                    + "Ask if I am a boy or girl, or tell me what other villagers said about me.";
        }

        // 8. Gender
        if (has(pad, "are you a boy", "are you a girl", "are you male", "are you female", "boy or girl",
                "are you a man", "are you a woman", "your gender", "are you he", "are you she")
                || w(tok, "gender")) {
            return pre + (me.female ? "I am a woman, thank you very much!" : "I am a man, last time I checked. Hrmm!");
        }

        // 9. Insults
        if (w(tok, "stupid", "idiot", "dumb", "ugly", "useless", "trash", "worthless", "moron", "loser", "jerk")) {
            me.setMood(Mood.ANGRY, now, 6000);
            addTrust(v, me, -1);
            return "How dare you! Hrmm! I will remember that.";
        }

        // 10. Compliments to the villager
        if (w(tok, "you", "youre", "ur") && firstOf(tok, COMPLIMENTS) != null) {
            me.setMood(Mood.HAPPY, now, 3000);
            addTrust(v, me, 1);
            return pick("*blushes* Hrmm! You are too kind.", "Hehe! Thank you, " + pn + "!");
        }

        // 11. Topics
        // 11a. death
        if (s(tok, "die", "dying", "dead", "death", "kill", "murder", "grave", "funeral", "ghost", "respawn",
                "corpse", "slain") || w(tok, "rip")) {
            int d = deaths.getOrDefault(id, 0);
            if (has(pad, "i died", "i keep dying", "i die", "i am dead", "im dead") || has(pad, "i just died")) {
                if (d > 0) {
                    return pre + "You have fallen " + d + (d == 1 ? " time" : " times")
                            + " while I watched. Hrmm. Be careful out there, " + pn + ".";
                }
                return pre + "Dying is hard on a person. Eat something and rest.";
            }
            if (w(tok, "kill", "murder") && w(tok, "you", "me")) {
                me.setMood(Mood.SCARED, now, 3000);
                return "Eep! Please do not! I have a family!";
            }
            if (villagerDeaths > 0) {
                return pre + "I have seen " + villagerDeaths + (villagerDeaths == 1 ? " villager" : " villagers")
                        + " pass away. It breaks the heart. We bury our fear and ring the bell.";
            }
            return pre + pick("Thankfully no villager has died lately. I plan to die of old age, in my bed.",
                    "Death comes for everyone, even emerald dealers. But not today. Hrmm.");
        }

        // 11b. money
        if (s(tok, "emerald", "money", "coin", "price", "cost", "rich", "poor", "broke", "pay", "buy", "sell",
                "trade", "bank", "cash", "afford", "expensive", "cheap", "loan", "borrow", "salary", "wealth")
                || has(pad, "give me")) {
            int em = emeraldsOf(c.p());
            if (has(pad, "give me", "lend me", "borrow", "loan")) {
                return pre + pick("Give you emeralds?! Hrmm. Nothing is free. Trade with me!",
                        "Ha! If I lent emeralds to everyone I would be a farmer's apprentice.");
            }
            if (has(pad, "are you rich", "how much do you have", "do you have money", "your savings", "how rich")) {
                return pre + "I have " + me.coins + " emeralds saved under my bed. "
                        + (me.coins > 30 ? "Not bad, hrmm?" : "Times are tough.");
            }
            if (w(tok, "poor", "broke")) {
                return pre + "Poor? Do not worry. Mine, farm, trade. Emeralds follow hard work.";
            }
            if (w(tok, "rich")) {
                return pre + "Rich is good, but friends are better. Hrmm.";
            }
            if (s(tok, "trade", "buy", "sell")) {
                return pre + "Right-click me and let's trade! Fair prices, always.";
            }
            if (em == 0) return pre + "Your pockets are empty of emeralds. Find some work, friend!";
            if (em >= 64) return pre + "You carry " + em + " emeralds?! You could buy half the village!";
            return pre + "You carry " + em + " emerald" + (em == 1 ? "" : "s")
                    + ". A fair purse. Spend it wisely, hrmm.";
        }

        // 11c. love / family
        if (has(pad, "do you love me", "do you like me", "am i your friend")) {
            if (marriedToPlayer(v, c.p())) return "Of course I love you, " + pn + "! You are my whole world.";
            if (me.trust >= 3) return pre + "I do like you, " + pn + ". You are a good friend.";
            return pre + "Hrmm. I hardly know you yet. Talk to me more!";
        }
        if (w(tok, "son", "mom", "mum", "dad", "kid", "kids")
                || s(tok, "family", "wife", "husband", "spouse", "marri", "child", "baby", "daughter", "mother",
                "father", "parent", "brother", "sister", "sibling", "grand", "uncle", "aunt", "partner")) {
            if (has(pad, "i have", "my mom", "my mum", "my dad", "my family", "my brother", "my sister",
                    "my kid", "my wife", "my husband", "my son", "my daughter")) {
                return pre + "Family is the best treasure, " + pn + ". Hold them close.";
            }
            if (s(tok, "mom", "mum", "mother")) {
                return pre + "My mother is " + tagVal(v, "vt_mom|") + ". She bakes the best bread in the village.";
            }
            if (s(tok, "dad", "father")) {
                return pre + "My father is " + tagVal(v, "vt_dad|") + ". Grumpy, but he loves me. Hrmm.";
            }
            int kids = intTag(v, "vt_kids|", 0);
            if (s(tok, "kid", "child", "baby", "son", "daughter")) {
                return pre + (kids == 0 ? "No children yet. Perhaps one day. Hrmm."
                        : "I have " + kids + (kids == 1 ? " child" : " children") + ". They run me ragged!");
            }
            Spouse sp = spouseOf(v);
            if (sp != null) {
                if (sp.player() && sp.id().equals(c.p().getUUID())) {
                    return pre + "I am married to YOU, " + pn + "! My dearest love.";
                }
                if (sp.player()) {
                    return pre + "I am married to " + sp.name() + ", a traveller. I miss them dearly.";
                }
                String title = sp.female() ? "wife" : "husband";
                return pre + "I am married to " + sp.name() + ", my " + title + ", and we have " + kids
                        + (kids == 1 ? " child" : " children") + ". Do you have family, " + pn + "?";
            }
            String lost = tagVal(v, "vt_lost|");
            if (lost != null) {
                return pre + "I was married to " + lost + ", but they have passed away. Hrmm... I still miss them.";
            }
            pending.put(id, new Pending(Q.FAMILY, now + 2400));
            return pre + "I am not married. My mother " + tagVal(v, "vt_mom|")
                    + " keeps matchmaking. Do you have family, " + pn + "?";
        }

        // 11d. armour
        if (s(tok, "armor", "armour", "helmet", "chestplate", "leggings", "boots", "protect")) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            String[] names = {"helmet", "chestplate", "leggings", "boots"};
            StringBuilder missing = new StringBuilder();
            int worn = 0;
            boolean battered = false;
            String tier = "";
            for (int i = 0; i < slots.length; i++) {
                ItemStack st = c.p().getItemBySlot(slots[i]);
                if (st.isEmpty()) {
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(names[i]);
                } else {
                    worn++;
                    if (tier.isEmpty()) tier = tierOf(st);
                    if (st.isDamaged() && st.getDamageValue() * 10 > st.getMaxDamage() * 7) battered = true;
                }
            }
            if (worn == 0) {
                return pre + "You wear NO armour?! One creeper and poof! Please get something on.";
            }
            String base = worn == 4 ? "Head to toe in armour! " : "You wear " + worn + " of 4 pieces. Missing: " + missing + ". ";
            String q = tier.isEmpty() ? "" : (tier.equals("netherite") || tier.equals("diamond")
                    ? "And " + tier + "! You look like a hero. " : "Decent " + tier + " gear. ");
            return pre + base + q + (battered ? "It looks battered though. See a smith!" : "");
        }

        // 11e. gear / what the player holds
        if (s(tok, "gear", "equip", "weapon", "sword", "pickaxe", "axe", "shovel", "hoe", "bow", "crossbow",
                "trident", "mace", "spear", "shield", "tool", "holding", "hand", "inventory", "item")) {
            ItemStack main = c.p().getMainHandItem();
            if (main.isEmpty()) {
                return pre + "Your hands are empty! A wanderer with nothing? Take a tool and get to work.";
            }
            String nm = main.getHoverName().getString();
            String tier = tierOf(main);
            String kind = kindOf(main);
            String comment;
            switch (tier) {
                case "netherite": comment = "Netherite! You have been to the Nether and lived. Respect."; break;
                case "diamond": comment = "Diamond! That is worth a hundred emeralds, hrmm."; break;
                case "iron": comment = "Iron. Solid, honest, reliable."; break;
                case "golden": comment = "Golden... pretty, but it breaks easily."; break;
                case "copper": comment = "Copper! Shiny and new-fangled."; break;
                case "stone": comment = "Stone. Humble, but it works."; break;
                case "wooden": comment = "Wood? You can do better than that!"; break;
                default: comment = kind.isEmpty() ? "Interesting thing to hold." : "A fine " + kind + ".";
            }
            boolean battered = main.isDamaged() && main.getDamageValue() * 10 > main.getMaxDamage() * 7;
            return pre + "You are holding " + nm + ". " + comment + (battered ? " It is nearly broken!" : "");
        }

        // 11f. emotions
        boolean askingMe = has(pad, "how are you", "how do you feel", "are you ok", "are you okay", "are you sad",
                "are you happy", "are you angry", "are you scared", "whats up", "what is up", "you ok");
        if (askingMe) {
            if (!me.grudges.isEmpty() && me.mood(now) == Mood.ANGRY) {
                return "Angry. I am still furious at " + me.grudges.iterator().next() + ". Hrmm!";
            }
            switch (me.mood(now)) {
                case HAPPY: return "I feel wonderful today! Hrmm hrmm!";
                case SAD: return "*sighs* Not so good. I miss someone.";
                case ANGRY: return "Angry. Very angry. Do not push me.";
                case SCARED: return "Scared... something does not feel right.";
                default: return "Calm and steady, thank you. How about you, " + pn + "?";
            }
        }
        if (has(pad, "i love you", "love you", "i like you")) {
            me.setMood(Mood.HAPPY, now, 4000);
            addTrust(v, me, 1);
            return "*blushes* Hrmm! You are too kind. I am already married to my work!";
        }
        if (has(pad, "i hate you", "hate you")) {
            me.setMood(Mood.SAD, now, 4000);
            return "*sniffles* That hurts, you know.";
        }
        if (s(tok, "sad", "depress", "cry", "lonely", "unhappy", "upset", "miss")) {
            return pre + "Oh, " + pn + ". I am sorry. Sit with me a while. The bell will ring and things will get better.";
        }
        if (s(tok, "angry", "mad", "furious", "rage", "annoy")) {
            return pre + "Breathe, friend. Hrmm. Take a walk around the farm.";
        }
        if (s(tok, "scared", "afraid", "nervous", "worried", "anxious", "fear")) {
            return pre + "Stay close to the iron golems. They protect us. You are safe here.";
        }
        if (s(tok, "happy", "glad", "excited", "joy", "cheer") && w(tok, "i", "im", "so", "very")) {
            me.setMood(Mood.HAPPY, now, 3000);
            return "Hrmm hrmm! I am so glad! Your joy lifts the whole village.";
        }
        if (s(tok, "bored", "boring")) {
            return pre + "Bored? Go farm, trade, or build a house. There is always work!";
        }

        // 11g. the village
        if (s(tok, "village", "home", "house", "bed", "golem", "raid", "pillager", "zombie", "monster", "safe",
                "farm", "crop", "library", "church", "bell", "neighbo", "creeper", "night", "rain", "weather")) {
            int vill = countNear(c, EntityType.VILLAGER, 48);
            int golems = countNear(c, EntityType.IRON_GOLEM, 48);
            int pillagers = countNear(c, EntityType.PILLAGER, 48);
            if (pillagers > 0) {
                me.setMood(Mood.SCARED, now, 3000);
                return "PILLAGERS! I see " + pillagers + " nearby! Ring the bell! Get inside!";
            }
            String weather = c.sl().isRaining() ? " It is raining, so the crops are happy." : "";
            String guard = golems == 0 ? " But there is no iron golem to guard us. Please build one!"
                    : " " + golems + " iron golem" + (golems == 1 ? "" : "s") + " watch over us.";
            return pre + "Our village has about " + vill + " villager" + (vill == 1 ? "" : "s") + " nearby." + guard + weather;
        }

        // 11h. name / greetings / thanks / bye
        if (has(pad, "your name", "who are you", "what are you called")) {
            return "I am " + me.name + ". Pleased to meet you, " + pn + "!";
        }
        if (w(tok, "hi", "hello", "hey", "yo", "sup", "greetings", "hola", "morning", "evening")) {
            if (R.nextDouble() < 0.4) return pre + "Hello " + pn + "! " + ask(c.p(), me, now);
            return pre + pick("Hrmm! Hello, " + pn + "!", "Welcome, friend!", "Hrmm hrmm. Good to see you, " + pn + ".");
        }
        if (w(tok, "thanks", "thank", "thx", "cheers")) {
            return pick("You are welcome! Hrmm.", "Anytime, friend!");
        }
        if (w(tok, "bye", "goodbye", "cya", "later", "farewell")) {
            return pick("Safe travels, " + pn + "!", "Hrmm. Come back soon!");
        }

        // 12. Fallback: sometimes ask a question, otherwise stay confused
        if (R.nextDouble() < 0.4) return pre + ask(c.p(), me, now);
        return pre + pick("Hrmm? I did not understand. Say 'help' to see what I know.",
                "Hrmm hrmm... what does that mean?",
                "Hrmm. Could you say that another way?");
    }
}
