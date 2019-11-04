package com.rockbite.tools.talos.editor.addons.bvb;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.esotericsoftware.spine.*;
import com.rockbite.tools.talos.TalosMain;
import com.rockbite.tools.talos.editor.widgets.propertyWidgets.*;
import com.rockbite.tools.talos.runtime.ParticleEffectDescriptor;


public class SkeletonContainer implements Json.Serializable, IPropertyProvider {

    private final BvBWorkspace workspace;

    private Skeleton skeleton;
    private AnimationState animationState;

    private Animation currentAnimation;
    private Skin currentSkin;

    private ObjectMap<String, ObjectMap<String, Array<BoundEffect>>> boundEffects = new ObjectMap<>();

    private Vector2 tmp = new Vector2();

    public SkeletonContainer(BvBWorkspace workspace) {
        this.workspace = workspace;
    }

    public void setSkeleton(FileHandle jsonFileHandle) {
        FileHandle atlasFileHandle = Gdx.files.absolute(jsonFileHandle.pathWithoutExtension() + ".atlas");
        jsonFileHandle = TalosMain.Instance().ProjectController().findFile(jsonFileHandle);
        atlasFileHandle = TalosMain.Instance().ProjectController().findFile(atlasFileHandle);

        setSkeleton(jsonFileHandle, atlasFileHandle);

        TalosMain.Instance().ProjectController().setDirty();
    }

    public void setSkeleton(FileHandle jsonHandle, FileHandle atlasHandle) {
        TextureAtlas atlas = new TextureAtlas(atlasHandle);
        SkeletonJson json = new SkeletonJson(atlas);

        json.setScale(1f); // should be user set
        final SkeletonData skeletonData = json.readSkeletonData(jsonHandle);

        skeleton = new Skeleton(skeletonData); // Skeleton holds skeleton state (bone positions, slot attachments, etc).
        skeleton.setPosition(0, 0);

        currentAnimation = skeleton.getData().getAnimations().get(0);
        currentSkin = skeleton.getData().getSkins().first();

        AnimationStateData stateData = new AnimationStateData(skeletonData); // Defines mixing (crossfading) between animations.
        animationState = new AnimationState(stateData); // Holds the animation state for a skeleton (current animation, time, etc).
        animationState.setTimeScale(1f);
        // Queue animations on track 0.
        animationState.setAnimation(0, currentAnimation, true);

        animationState.update(0.1f); // Update the animation time.
        animationState.apply(skeleton); // Poses skeleton using current animations. This sets the bones' local SRT.\
        skeleton.setPosition(0, 0);
        skeleton.updateWorldTransform(); // Uses the bones' local SRT to compute their world SRT.

        animationState.addListener(new AnimationState.AnimationStateAdapter() {
            @Override
            public void event(AnimationState.TrackEntry entry, Event event) {
                super.event(entry, event);

                for(BoundEffect boundEffect: getBoundEffects()) {
                    String startEvent = boundEffect.getStartEvent();
                    String completeEvent = boundEffect.getCompleteEvent();
                    if(startEvent.equals(event.getData().getName())) {
                        boundEffect.startInstance();
                    }
                    if(completeEvent.equals(event.getData().getName())) {
                        boundEffect.completeInstance();
                    }
                }
            }

            @Override
            public void start(AnimationState.TrackEntry entry) {
                for(BoundEffect boundEffect: getBoundEffects()) {
                    String eventName = boundEffect.getStartEvent();
                    if(eventName.equals("")) {
                        boundEffect.startInstance();
                    }
                }
                super.start(entry);
            }

            @Override
            public void end(AnimationState.TrackEntry entry) {
                for(BoundEffect boundEffect: getBoundEffects()) {
                    String eventName = boundEffect.getCompleteEvent();
                    if(eventName.equals("")) {
                        boundEffect.completeInstance();
                    }
                }
                super.end(entry);
            }
        });
    }

    public void update(float delta, boolean isSkeletonPaused) {
        if(skeleton == null) return;

        if(!isSkeletonPaused) {
            animationState.update(delta);
            animationState.apply(skeleton);
        }

        for(BoundEffect effect: getBoundEffects()) {
            effect.update(delta);
        }
    }


    public Skeleton getSkeleton() {
        return skeleton;
    }

    public AnimationState getAnimationState() {
        return animationState;
    }

    public float getBoneRotation(String boneName) {
        Bone bone = skeleton.findBone(boneName);
        if(bone != null) {
            return bone.getRotation();
        }

        return 0;
    }

    public float getBonePosX(String boneName) {
        Bone bone = skeleton.findBone(boneName);
        if(bone != null) {
            return bone.getWorldX();
        }

        return 0;
    }

    public float getBonePosY(String boneName) {
        Bone bone = skeleton.findBone(boneName);
        if(bone != null) {
            return bone.getWorldY();
        }

        return 0;
    }

    public Array<BoundEffect> getBoundEffects(String skinName, String animationName) {
        if(boundEffects.get(skinName) == null) {
            boundEffects.put(skinName, new ObjectMap<String, Array<BoundEffect>>());
        }
        ObjectMap<String, Array<BoundEffect>> animations = boundEffects.get(skinName);
        if(animations.get(animationName) == null) {
            animations.put(animationName, new Array<BoundEffect>());
        }

        return animations.get(animationName);
    }

    public Array<BoundEffect> getBoundEffects() {
        if(boundEffects.get(currentSkin.getName()) == null) {
            boundEffects.put(currentSkin.getName(), new ObjectMap<String, Array<BoundEffect>>());
        }
        ObjectMap<String, Array<BoundEffect>> animations = boundEffects.get(currentSkin.getName());
        if(animations.get(currentAnimation.getName()) == null) {
            animations.put(currentAnimation.getName(), new Array<BoundEffect>());
        }

        return animations.get(currentAnimation.getName());
    }

    public BoundEffect addEffect(String skinName, String animationName, BoundEffect effect) {
        getBoundEffects(skinName, animationName).add(effect);

        return effect;
    }

    public BoundEffect addEffect(String name, ParticleEffectDescriptor descriptor) {
        BoundEffect boundEffect = new BoundEffect(name, descriptor, this);
        boundEffect.setForever(true);

        getBoundEffects().add(boundEffect);

        return boundEffect;
    }

    public Bone findClosestBone(Vector2 pos) {
        Bone closestBone = skeleton.getRootBone();
        float minDist = getBoneDistance(closestBone, pos);

        for(Bone bone: skeleton.getBones()) {
            float dist = getBoneDistance(bone, pos);
            if(minDist > dist) {
                minDist = dist;
                closestBone = bone;
            }
        }
        return closestBone;
    }

    public float getBoneDistance(Bone bone, Vector2 pos) {
        tmp.set(pos);
        return tmp.dst(bone.getWorldX(), bone.getWorldY());
    }

    public Bone getBoneByName(String boneName) {
        return skeleton.findBone(boneName);
    }

    public BoundEffect updateEffect(String name, ParticleEffectDescriptor descriptor) {
        for(ObjectMap<String, Array<BoundEffect>> skins: boundEffects.values()) {
            for(Array<BoundEffect> animations: skins.values()) {
                for(BoundEffect effect: animations) {
                    if(effect.name.equals(name)) {
                        // found it
                        effect.updateEffect(descriptor);
                        return effect;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void write(Json json) {
        if(skeleton == null) return;

        json.writeValue("skeletonName", skeleton.getData().getName());
        for(String skinName: boundEffects.keys()) {
            for(String animationName: boundEffects.get(skinName).keys()) {
                json.writeArrayStart("boundEffects");
                for(BoundEffect effect: boundEffects.get(skinName).get(animationName)) {
                    json.writeObjectStart();
                    json.writeValue("skin", skinName);
                    json.writeValue("animation", animationName);
                    json.writeValue("data", effect);
                    json.writeObjectEnd();
                }
                json.writeArrayEnd();
            }
        }
        json.writeValue("currSkin", currentSkin.getName());
        json.writeValue("currAnimation", currentAnimation.getName());
    }

    @Override
    public void read(Json json, JsonValue jsonData) {
        String skeletonName = jsonData.getString("skeletonName");
        String skeletonPath = workspace.getPath(skeletonName + ".json");
        FileHandle jsonHandle = TalosMain.Instance().ProjectController().findFile(skeletonPath);

        setSkeleton(jsonHandle);

        boundEffects.clear();
        // now let's load bound effects
        JsonValue boundEffects = jsonData.get("boundEffects");
        for(JsonValue boundEffect: boundEffects) {
            String skin = boundEffect.getString("skin");
            String animation = boundEffect.getString("animation");
            JsonValue data = boundEffect.get("data");
            BoundEffect effect = new BoundEffect();
            effect.setParent(this);
            effect.read(json, data);
            addEffect(skin, animation, effect);
        }

        String currentSkinName = jsonData.getString("currSkin", currentSkin.getName());
        String currentAnimationName = jsonData.getString("currAnimation", currentAnimation.getName());

        currentSkin = skeleton.getData().findSkin(currentSkinName);
        currentAnimation = skeleton.getData().findAnimation(currentAnimationName);
        animationState.setAnimation(0, currentAnimation, true);
    }

    public void clear() {
        skeleton = null;
        boundEffects.clear();
    }

    public BvBWorkspace getWorkspace() {
        return workspace;
    }

    @Override
    public Array<PropertyWidget> getListOfProperties() {

        Array<PropertyWidget> properties = new Array<>();

        LabelWidget skeletonName = new LabelWidget("skeleton name") {
            @Override
            public String getValue() {
                if(skeleton != null) {
                    return skeleton.getData().getName();
                }
                return "N/A";
            }
        };

        SelectBoxWidget currentSkinWidget = new SelectBoxWidget("skin") {
            @Override
            public Array<String> getOptionsList() {
                if(skeleton != null) {
                    Array<String> result = new Array<>();
                    for(Skin skin : skeleton.getData().getSkins()) {
                        result.add(skin.getName());
                    }
                    return result;
                }
                return null;
            }

            @Override
            public String getValue() {
                if(currentSkin != null) {
                    return currentSkin.getName();
                }
                return "N/A";
            }

            @Override
            public void valueChanged(String value) {
                currentSkin = skeleton.getData().findSkin(value);
            }
        };

        SelectBoxWidget currentAnimationWidget = new SelectBoxWidget("animation") {
            @Override
            public Array<String> getOptionsList() {
                if(skeleton != null) {
                    Array<String> result = new Array<>();
                    for(Animation animation : skeleton.getData().getAnimations()) {
                        result.add(animation.getName());
                    }
                    return result;
                }
                return null;
            }

            @Override
            public String getValue() {
                if(currentAnimation != null) {
                    return currentAnimation.getName();
                }
                return "N/A";
            }

            @Override
            public void valueChanged(String value) {
                currentAnimation = skeleton.getData().findAnimation(value);
                animationState.setAnimation(0, currentAnimation, true);

                workspace.effectUnselected(workspace.selectedEffect);
            }
        };

        properties.add(skeletonName);
        properties.add(currentSkinWidget);
        properties.add(currentAnimationWidget);

        return properties;
    }

    @Override
    public String getPropertyBoxTitle() {
        return "Skeleton Properties";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    public BoundEffect getEffectByName(String selectedEffect) {
        if(selectedEffect == null) return null;
        for(BoundEffect effect: getBoundEffects()) {
            if(effect.name.equals(selectedEffect)) {
                return effect;
            }
        }

        return null;
    }

}
