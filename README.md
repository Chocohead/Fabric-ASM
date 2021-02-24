# Manningham Mills
In a harmonious world of Mixins and the occasional scary reflective hack, Fabric resides. For a simple injection of additional code or even a redirection of a method invocation Mixins are most suitable, providing clear rules for what is possible, and solid runtime checking that what is being done is possible. When other changes need to be made, be it changing an `if` decision or when a loop `break`s, Mixins suddenly prove to not be quite so suitable. Gone is the nice convenience of an easy annotation. You're either cloning half the method and cancelling in a clunky `@Inject` or even worse flattening everything with an `@Overwrite`.

Taking a step away to work on another project you're trying to inject into a method which has a private class as a parameter, a most disastrous occasion. If it is an interface reflection comes to your rescue, in the form of a classically unsafe [`InvocationHandler`](https://github.com/ezterry/ezwasteland/blob/1.14.fabric/src/main/java/com/ezrol/terry/minecraft/wastelands/EzwastelandsFabric.java#L119-L182), allowing the interface to be "pretend" extended. If it's a class even further trouble, a `@Coerce` and a prayer perhaps.

Say you need to extend the private class. Mixin has completely abandoned you to fend for yourself. Sure you can theoretically `@Accessor` `<init>`, but that's no use for extending when Java won't take your fake `<init>` as a proper super constructor to call. A repackaging hack might get you into a package private class at a push, but at that point you've left the bay of safety and are out on the open waters of shenanigans. A properly private class is beyond reachable even with that. What you could do with is not a raft of repackaging, but the battleship that is access transformers.

## Sailing the Shenanigans
### Defining the Ship
A mod's access transformer is defined by [Loom](https://github.com/Chocohead/fabric-loom) during dev time. For production code the access transformer is automatically remapped from [Yarn](https://github.com/FabricMC/yarn) to [Intermediary](https://github.com/FabricMC/intermediary) names, and moved to `silky.at` in the root of the produced jar. MM will automatically load and apply any transformations at runtime, `IllegalAccessException`s will be thrown if it is not present. Loom applies any transformations for development, so MM is not needed unless another mod adding an AT is added to the classpath.

The provided example's AT is defined in the `build.gradle` [here](build.gradle#L33).

### Structuring Sensibly
Within the context of Fabric, access transformers are only really needed to make classes public (to allow them to be directly referenced and thus possibly extended) and methods public (to allow them to be extended, especially for constructors). Thus a more simplified format of access transformers is used in MM to account for not needing to handle fields or non-public transformations.

```
# Any line starting with a hash are ignored
# As are any empty lines

# Classes defined alone are interpreted to mean the class is to be made public and definalised
net/minecraft/item/ItemStack

# Classes defined with a method description are interpreted to mean the method is to be made public and definalised
net/minecraft/item/ItemStack <init>(Lnet/minecraft/item/ItemProvider;)V

# Loom will crash when any wrongly or ambiguously defined transformations are provided
# MM will merely fail to transform invalid methods, whilst invalid classes will crash
#net/minecraft/item/ItemStack <init>
# Will go bang if uncommented  ^^^
```

The provided example's AT is defined [here](example/resources/access-transformations.txt)

### Sailing Safely
Now you've left the bay of safety, there is not quite the same degree of protection Mixin provides. Whilst for access transformers there is relatively little that can go wrong, it is always worth remembering the golden rules:

#### Avoid transforming anything that doesn't need to be
Every transformation is another slightly more brittle bit of your mod for updates going forward

#### Be wary of transforming protected methods to be public
Any other mod which extends the method will crash if they've kept the method protected (which they are perfectly entitled to do). Whilst this is theoretically possible to fix, it would involve sniffing all loaded mods to fix the class hierarchy. Knock yourself out if you want to implement that, if it works I'll take it.

#### Make sure the transformations being made in dev export properly to production
If multiple mod jars are being exported by a single project, care should be taken that any mods that need the transformations get them. A project can only have a single AT defined - which will be added to the `jar` & `sourcesJar` tasks as well as all tasks with the [`RemapJarTask`](https://github.com/Chocohead/fabric-loom/blob/leaf/src/main/java/net/fabricmc/loom/task/RemapJarTask.java) & [`RemappingJar`](https://github.com/Chocohead/fabric-loom/blob/leaf/src/main/java/net/fabricmc/loom/task/RemappingJar.java) types by default. This is can be configured:

To disable the main `jar` task having the AT remapped and exported...
```groovy
jar {
	AT.include = false
}
```
To disable the `sourcesJar` task having the AT exported...
```groovy
sourcesJar {
	AT.include = false
}
```
Whilst the `RemapJarTask` and `RemappingJar` tasks can have AT remap and export disabled via the `includeAT` property:
```groovy
task exampleJar(type: RemappingJar, dependsOn: exampleClasses) {
	from sourceSets.example.output
	includeAT = false
}
```

Note for ATs purely being used for `@Mixin` targets, they do not *need* to be present at runtime given Mixin does not class load the types directly. Their presence does not hurt if other ATs (which are needed at runtime) necessitate it however.

---

Access transformers are pretty neat and all, but they still have limits. You can transform an `enum`'s constructor be to public yet that doesn't get you very far in terms of being able to add new entries. For that a detour to the extender's cove is needed.

## Exploiting Extender's Cove
### Waiting for High Tide
Registering additions that want to be made has to happen very early on in the mod loading process. As a result it is quite reasonable to expect that a given mod's initialisers are yet to be run. To resolve this (and the class loading pitfalls described below), MM has a system of "Early Risers" which are classes that implement `Runnable` and are called as early as when necessary.

Early Risers are defined via an [entrypoint](https://fabricmc.net/wiki/documentation:entrypoint) defined like normal in a mod's `fabric.mod.json`. The Early Riser entrypoint name is `mm:early_risers`, and it uses the type `Runnable` so will have `run` called on entry. The provided example's Early Riser definition is [here](example/resources/fabric.mod.json#L20-L22).

### Navigating the Entrance
The earliness that extensions require poses potential pitfalls from class loading. Given the main launch class will get to be resolved, any accidental reference to a class that might have Mixins that need to be applied will certainly result in a sad time. As a consequence there's a great need to be careful the for classes that might be loaded by an Early Riser.

For the purposes of extending `enum`s, there are four methods provided in [`ClassTinkerers`](src/com/chocohead/mm/api/ClassTinkerers.java):
* [`enumBuilder(String)`](src/com/chocohead/mm/api/ClassTinkerers.java#L163)
* [`enumBuilder(String, Class...)`](src/com/chocohead/mm/api/ClassTinkerers.java#L181)
* [`enumBuilder(String, String...)`](src/com/chocohead/mm/api/ClassTinkerers.java#L206)
* [`enumBuilder(String, Object...)`](src/com/chocohead/mm/api/ClassTinkerers.java#L234)

Whilst on the surface it may appear a subtle difference between taking a `Class`, `String` or `Object` array, the difference is vital for the purposes of avoiding undesired class loading.

#### The Quick Way In
The first method is comparatively the simplest but also the least flexible. In the case where the desired `enum` constructor takes no parameters, this method can be used to save needing to pass an empty array into one of the other three methods. Indeed, passing an empty array to any of the other three methods is functionally equivalent to using this one. Where the constructor does take parameters however, this method is not suitable (and will not work) so another one of the methods should be used.

#### The Easy Way In
The second method is practically easier to use. The variable argument represents the parameter types the desired `enum` constructor takes that is wanted to be used, thus is it only natural that `Class` arguments are passed in. For Java or library types this is all fine, but when it comes to Minecraft types this poses a big problem. By mentioning the class it has to be class loaded so Java definitely knows it exists (as well as to know the properties of the definition). `ClassTinkerers` therefore checks for if any of the passed in classes are in the `net.minecraft` package and will crash if they are. This is of course a tad crude considering there are classes like `GlStateManager` that are not in the package but are still Mixinable, but it's better than nothing to try save compatibility.

#### The Safe Way In
The third method requires a little more effort. Like the second, the variable arguments represents the parameter types for the desired constructor. But unlike the second, instead of using the `Class` object it uses the internal name (similar to Mixin). This is obviously less safe as classes have different names for development environments compared to the game normally running but this is the price you pay. The benefit (as you might have guessed) is that using the internal names bypasses class loading as Java doesn't need to know the types exist even when it goes looking for the constructor.

It's important to remember that (slightly confusingly) the first argument is not the internal name of the `enum` but the normal class name. This is partly for consistency between the methods and partly because the internal name would be a little unhelpful given only the class name is actually needed to find the `enum` being extended.

#### The Lazy Way In
The fourth method is in effect a combination of the previous two. Like them, the variable argument represents the parameter types for the desired constructor. Those types however can be specified either as a `Class`, the internal name `String` or directly as ASM `Type` objects. This offers the best of both worlds in that non-Minecraft classes can be specified directly without forcing the Minecraft ones to also be. Always nice to have choices.

### Docking at the Jetty
Regardless of which of the methods you use, the resulting return object will be an [`EnumAdder`](src/com/chocohead/mm/api/EnumAdder.java) for your chosen `enum` constructor. It allows adding as many values as you theoretically want, once again with a choice of methods to do so:
* [`addEnum(String, Object...)`](src/com/chocohead/mm/api/EnumAdder.java#L158)
* [`addEnum(String, Supplier)`](src/com/chocohead/mm/api/EnumAdder.java#L179)

The first takes the value's name and potential parameters directly as pre-created objects. This is helpful for a constructor that might not need any additional arguments or one which uses Java/Library only types. The second also takes the value's name but takes a factory which returns an array of parameters to be as is needed. This allows guarding types that would otherwise be loaded behind a lambda, thus avoiding any more clunky strings to be passed around instead.

The provided example uses both of these methods to demonstrate [here](example/src/com/chocohead/mm/testing/EarlyRiser.java#L22-L29).

Once the desired values are added, [`EnumAdder#build`](src/com/chocohead/mm/api/EnumAdder.java#L274) must be called in order for the changes to be actually applied. This makes using `EnumAdder` as a builder look a little more Java-y, but also registers the changes to be applied as a single block rather than piecewise per addition which provides a small boost to class transforming speed. It is worth nothing that trying to add any more values once `build` is called will end poorly.

#### Misloading the Raft
If an invalid (normally wrongly defined) constructor is specified and attempted to be used, this will be picked up during transforming and the game will crash with a `NoSuchMethodError`. Silent exceptions are asking for trouble down the line after all. Duplicate or otherwise invalid value names will throw a `ClassVerifyError` if the JVM isn't happy, ultimately it's the arbiter of what's good and what isn't.

There is also a degree of trust that the given parameter objects actually match the types the constructor is expecting, you'll get `ClassCastException`s if they aren't. Supplying the wrong number of parameters relative to what the constructor takes will result in an `IllegalArgumentException`.

### Landing Amphibiously
There are times when a jetty alone is insufficient for the extension task at hand, resulting in big problems for any added values. In such situations a full amphibious landing to the cove's shore can be performed instead.

Any situation where a method in the `enum` needs to be subclasses, such as an abstract `enum`, adding values directly will result in `AbstractMethodError`s when the methods are called for the added values. The constructor alone is unable to provide a solution to this; hence MM allows subclassing enums for added entries. These subclasses are defined via an additional structure class (to the constructor's parameters) which defines the overrides a subclass would like to make to the `enum`. Like normal addition there are two options depending how the normal constructor parameters are passed:
* [`addEnumSubclass(String, String, Object...)`](src/com/chocohead/mm/api/EnumAdder.java#L205)
* [`addEnumSubclass(String, String, Supplier)`](src/com/chocohead/mm/api/EnumAdder.java#L232)

The first and third parameters act just like with normal addition. The second is the internal name of the structure class for the subclass. Such structure classes can themselves extend other classes (thus technically supporting typical class inheritance despite being an `enum`) but should always ultimately extend an **unregistered** abstract Mixin to the target `enum` with the appropriate `@Shadow`ed abstract methods that are desired to be overriden. Whilst an unusual approach this allows the Mixin annotation processor to handle the obfuscation, without Mixin needing to do any actual injecting at runtime. Instead at runtime the Mixin class is removed from the structure class's hierarchy to be replaced `Object` (hence it must be the deepest parent) and the method implementations called directly from the subclass.

Structure classes can be reused for multiple additions, with a single instance being created for each addition made using it. This is stored within the subclass to be used for handling method calls. Subsequently the structure class can store its own state via fields if desired. It is strongly discouraged to use the structure class outside of `enum` extension for class loading and general sanity reasons.

An example [structure class](example/src/com/chocohead/mm/testing/LetThemEnchantCake.java) with [registration](example/src/com/chocohead/mm/testing/EarlyRiser.java#L37) and [use](example/src/com/chocohead/mm/testing/ExampleMod.java#L54-L55) is provided to demonstrate practical usage extending `EnchantmentTarget`.

### Plundering the Booty
Since adding to an `enum` is done during class loading, getting the entries is likely to need to happen elsewhere in the code base. In fact it should happen elsewhere, as class loading the enum you're trying to add onto is quite foolish. MM adds a utility method for getting added entries: [`ClassTinkerers#getEnum(Class, String)`](src/com/chocohead/mm/api/ClassTinkerers.java#L265). It is fail fast, so any problems adding onto the `enum` that weren't picked up during transforming will make themselves clear there. Also worth caching the result if you're using it in more than one place as it is `O(n)` with `n` how big the given `enum` is.

The provided example uses this [here](example/src/com/chocohead/mm/testing/ExampleMod.java#L37).

## Extending the Map
There are times where the existing class and resource map is insufficient, and an extension to it is desired. These patches can be attached at runtime dynamically as needed to allow loading out of additional jars. All additions are added in the form of `URL`s for the mod `URLClassLoader` to go looking in via [`ClassTinkerers#addURL(URL)`](src/com/chocohead/mm/api/ClassTinkerers.java#L75). If this is called too early (ie before Early Risers run) this will fail and return `false`, otherwise it will add the given `URL` to the classpath and return `true`.

---

Say you're getting a feeling for the Mixinless world and want to go deeper. The sea of shenanigans is a passageway to the bigger ocean of emancipation that is raw ASM. Throwing the chains of any notion of safety away and caution to the wind represents the apex of ~~breaking absolutely everything~~ reaching Fabric's full potential.

## Throwing the Map Away
### Class Generation
The first step to ASM enlightenment is to be able to generate whatever class you want. Whilst of course trying to redefine classes that already exist isn't going to work out, there's a practically infinite pool of alternative class names you can come up with to generate whatever you want. What's more classes can be generated at any time, as soon as there's a definition registered they can be loaded and used.

Defining a class is as simple as picking the name, then giving that and the class bytes to [`ClassTinkerers#define(String, byte[])`](src/com/chocohead/mm/api/ClassTinkerers.java#L90). The class bytes can be generated using the standard ASM `ClassWriter`, it's not really anticipated that you'll manually work out what you need. If a class with the given name has already been defined using the method it will skip the additional definition and return `false`. If a class already exists on the classpath with the same name the behaviour is undefined (don't do that).

### Class Modification
Now classes can be defined at will, ASM enlightenment is surely closer. But to create a new class is not nearly as powerful as to change an existing one as desired. Sure if the class has been loaded it's too late, but to transform a class (before that point) without being limited by Mixins is the ultimate goal.

Class transformations are done via registering a `ClassNode` `Consumer` for a given class to [`ClassTinkerers#addTransformation(String, Consumer)`](src/com/chocohead/mm/api/ClassTinkerers.java#L149). This means as many transformations as desired can be added for any class. Like adding to `enum`s, this needs to be done from an Early Riser so that all the classes being transformed are known in time before the game starts.

### Class Replacement
There are rare occasions where a transformation to a class is so extensive any immediate compatibility around changes that might have been made prior are impractical. In such occasions there is a more decisive option to just transforming an existing class, replacing it outright. Replacement should not be taken lightly as only a single replacement can be registered for any individual class.

Class replacements are done via registering a `ClassNode` `Consumer` for a given class to [`ClassTinkerers#addReplacement(String, Consumer)`](src/com/chocohead/mm/api/ClassTinkerers.java#L120) similarly to transformations. Like normal class transformations, this needs to be done from an Early Riser. If a replacement is already registered for the given name an `IllegalStateException` will be thrown.

---

With that you are now as free as practically possible to change whatever you like within the Fabric ecosystem. Now out on the open waters of the raw ASM filled ocean, everything you do is mostly unchecked and anything that goes wrong (or indeed doesn't go wrong) can be down to you. Bon voyage!

PS: if you are ever lost at sea, confused by generated and/or modified classes, remember Mixin can help you out with the VM arg [`-Dmixin.debug.export=true`](https://github.com/SpongePowered/Mixin/wiki/Mixin-Java-System-Properties). MM fully supports this for all classes which it changes and generates.

## Culture Me Up
[Manningham Mills](https://en.wikipedia.org/wiki/Lister_Mills) (or Lister Mills when trying to mask the fact it's in Manningham) was once the world's largest silk and velvet textiles factory. Built to replace the original mills destroyed by fire in 1871, the now Grade II listed building contained 27 acres of floor space to fit over 11,000 employees making high quality textiles. Estimated to weigh around 8000 imperial tons, the 249 feet high chimney acts as a beacon to attract house buyers to luxury apartments given it can do little else ever since the mill closed down in 1999 and was converted into an apartment complex.
Not that Manningham is a place you should aspire to live in now. Or go to really.
