package com.familyremote.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.familyremote.app.child.ChildHomeFragment
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityMainBinding
import com.familyremote.app.parent.ParentHomeFragment

/**
 * Single top-level host for the two role tabs (parent mode / child mode), per docs/plans/README.md
 * §2. Parent mode is the default tab shown on launch (spec.md §4).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_parent -> ParentHomeFragment()
                R.id.nav_child -> ChildHomeFragment()
                else -> return@setOnItemSelectedListener false
            }
            showFragment(fragment)
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_parent
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.tabFragmentContainer, fragment)
        }
    }
}
